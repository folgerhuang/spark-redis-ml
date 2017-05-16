import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import scala.collection.mutable
import scala.language.reflectiveCalls
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.ml.classification.{RandomForestClassificationModel, RandomForestClassifier}
import org.apache.spark.ml.feature.{IndexToString, StringIndexer, VectorIndexer}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.regression.{RandomForestRegressionModel, RandomForestRegressor}
import org.apache.spark.ml.tree.{CategoricalSplit, ContinuousSplit, Split}
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.sql.{SparkSession, _}
import redis.clients.jedis.Protocol.Command
import redis.clients.jedis.{Jedis, _}
import com.redislabs.client.redisml.MLClient
import com.redislabs.provider.redis.ml.Forest
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator

object ForestTest {
  def main(args: Array[String]) {
    val conf = new SparkConf().setAppName("Forest Example")
    val sc = new SparkContext(conf)

    def hola(s:String = "Mundo", n: Int = 5) {
      for (i <- 0 to n) {
        println(s"Hola ${s} ${i}")
      }
    }

    hola(args(0), args(1).toInt)



    val nTrees = args(1).toInt
    val spark = SparkSession
      .builder
      .getOrCreate()
    // Load and parse the data file, converting it to a DataFrame.
    //val data = spark.read.format("libsvm").load("data/mllib/small_test_10L_2F_np")
    val data = spark.read.format("libsvm").load(args(0))

    // Index labels, adding metadata to the label column.
    // Fit on whole dataset to include all labels in index.
    val labelIndexer = new StringIndexer().setInputCol("label").setOutputCol("indexedLabel").fit(data)
    // Automatically identify categorical features, and index them.
    // Set maxCategories so features with > 4 distinct values are treated as continuous.
    val featureIndexer = new VectorIndexer().setInputCol("features").setOutputCol("indexedFeatures").setMaxCategories(20).fit(data)

    // Split the data into training and test sets (30% held out for testing).
    val Array(trainingData, test) = data.randomSplit(Array(0.8, 0.2))

    // Train a RandomForest model.
    val rf = new RandomForestClassifier().setFeatureSubsetStrategy("all").setLabelCol("indexedLabel").setFeaturesCol("indexedFeatures").setNumTrees(nTrees)

    // Convert indexed labels back to original labels.
    val labelConverter = new IndexToString().setInputCol("prediction").setOutputCol("predictedLabel").setLabels(labelIndexer.labels)

    // Chain indexers and forest in a Pipeline.
    val pipeline = new org.apache.spark.ml.Pipeline().setStages(Array(labelIndexer, featureIndexer, rf, labelConverter))

    // Train model. This also runs the indexers.
    val model = pipeline.fit(trainingData)

    // Make predictions.
    val predictions = model.transform(test)

    // Select example rows to display.
    predictions.select("predictedLabel", "label", "features").show(5)

    // Select (prediction, true label) and compute test error.
    val evaluator = new MulticlassClassificationEvaluator().setLabelCol("indexedLabel").setPredictionCol("prediction").setMetricName("accuracy")
    val accuracy = evaluator.evaluate(predictions)
    println("Test Error = " + (1.0 - accuracy))

    val rfModel = model.stages(2).asInstanceOf[RandomForestClassificationModel]
    println("Learned classification forest model:\n" + rfModel.toDebugString)

    val f = new Forest(rfModel.trees)
    f.loadToRedis("forest-test", "localhost")

    val localData = featureIndexer.transform(test).collect

    def makeInputString(i: Int): String = {
      val sparseRecord = localData(i)(2).asInstanceOf[org.apache.spark.ml.linalg.SparseVector]
      val indices = sparseRecord.indices
      val values = sparseRecord.values
      var sep = ""
      var inputStr = ""
      for (i <- 0 to ((indices.length - 1))) {
        inputStr = inputStr + sep + indices(i).toString + ":" + values(i).toString
        sep = ","
      }
      inputStr
    }

    def makeDF(i: Int): org.apache.spark.sql.DataFrame = {
      test.sqlContext.createDataFrame(sc.parallelize(test.take(i + 1).slice(i, i + 1)), test.schema)
    }

    var redisRes = ""
    var sparkRes = 0.0
    var rtotal = 0.0
    var stotal = 0.0
    var diffs = 0.0
    def benchmark(b: Int) {
      rtotal = 0.0
      stotal = 0.0
      diffs = 0.0
      val jedis = new Jedis("localhost")
      for (i <- 0 to b) {
        val rt0 = System.nanoTime()
        jedis.getClient.sendCommand(MLClient.ModuleCommand.FOREST_RUN, "forest-test", makeInputString(i))
        redisRes = jedis.getClient().getStatusCodeReply
        val rt1 = System.nanoTime()
        println("Redis time: " + (rt1 - rt0) / 1000000.0 + "ms, res=" + redisRes)
        val df = makeDF(i)
        val st0 = System.nanoTime()
        val rawSparkRes = model.transform(df)
        val st1 = System.nanoTime()
        sparkRes = rawSparkRes.select("prediction").asInstanceOf[org.apache.spark.sql.DataFrame].take(1)(0)(0).asInstanceOf[Double]
        println("Spark time: " + (st1 - st0) / 1000000.0 + "ms, res=" + sparkRes)
        println("---------------------------------------");
        if (sparkRes - redisRes.toFloat != 0) {
          diffs += 1
        }
        rtotal += (rt1 - rt0) / 1000000.0
        stotal += (st1 - st0) / 1000000.0
      }
      println("Classification averages:")
      println(s"redis: ${rtotal / b.toFloat} ms")
      println(s"spark: ${stotal / b.toFloat} ms")
      println(s"ratio: ${stotal / rtotal}")
      println(s"diffs: $diffs")
    }

    benchmark(30)
    sc.stop()
  }
}