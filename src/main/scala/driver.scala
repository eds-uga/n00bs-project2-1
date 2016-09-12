import java.io.PrintWriter

import scala.collection.immutable.Vector
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.log4j.Logger
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.classification.{NaiveBayes, RandomForestClassificationModel, RandomForestClassifier}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{IndexToString, StringIndexer, VectorIndexer}

import scala.collection.mutable

//import scala.Vector
import scala.collection.Map

object driver {
  var appName = "MalwareClassifier"
//  var master = "spark://54.149.171.83:7077"
  var master = "local[8]"
  var executor = "10g"
  var conf: SparkConf = null
  var sc: SparkContext = null
  val log = Logger.getLogger(getClass.getName)
  def initialize()
  {
      conf = new SparkConf()
      .setAppName(appName)
      .setMaster(master)
      .set("spark.executor.memory", executor)
      .set("spark.sql.warehouse.dir", "spark-warehouse")
      .set("spark.network.timeout", "6000")
      .set("spark.executor.heartbeatInterval", "600")

     sc = new SparkContext(conf)
    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "AKIAISHYBNDYMKIBCDUQ")
    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "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" )


  }
}
/**
  * Created by UNisar on 9/2/2016.
  */
class driver (xTrain: String, yTrain: String, xTest: String, binariesPath: String, resultsPath: String, numberOfGrams: Int) extends Serializable {
  import driver._
  val sqlContext = new org.apache.spark.sql.SQLContext(sc)
  import sqlContext.implicits._

  val corpusIterator = sc.textFile(xTrain).zipWithIndex().map(c => (c._2, c._1)).cogroup(sc.textFile(yTrain).zipWithIndex().map(c => (c._2, c._1))).map ( x => (x._2._2.head, x._2._1.head))

  def readFile(inputPath: String) = {
    val fullPath = binariesPath + inputPath + ".bytes"
//val fullPath = inputPath

    sc.textFile(fullPath).
      map(x => x.split(" ").drop(1).
        sliding(numberOfGrams).map(_.mkString(""))).flatMap(c => c).countByValue().filter ( !_._1.contains('?'))

  }

  def createCombiner(input: Map[String, Long]) = {
    var map = new mutable.HashMap[String, Long]
    for ( (k,v) <- input)
      map.put(k, v)
    map
  }

  def mergeValue( input: mutable.Map[String, Long], output: Map[String, Long]) = {
    for (( k,v) <- output )
        input.put(k, input.getOrElse(k, 0L) + 1L)
    input
  }

  def mergeCombiner ( input: mutable.Map[String, Long], output: mutable.Map[String, Long]) =
  {
    for ((k,v) <- output)
      input.put(k, input.getOrElse(k, 0L) + 1L)
    input
  }

  def run: Unit = {

//    readFile("Data/01SuzwMJEIXsK7A8dQbl.bytes")
    val finalOutput = corpusIterator.map ( item =>
      {
        var output = (item._1.toInt).toString
        val map: Seq[(String, Long)] = readFile(item._2).toSeq.sortWith((u,v) => u._2 > v._2).map ( x => (x._1, x._2))
          .sortBy(_._1)
        for ( m <- map)
              output = output.concat(" " + (Integer.parseInt(m._1, 16)+1) + ":" + m._2)
        output
      })
    finalOutput.coalesce(1).saveAsTextFile(resultsPath)
  }

  def generateTest: Unit = {
    val finalOutput = sc.textFile(xTest).map ( item =>
    {
      var output="0"
      val map: Seq[(String, Long)] = readFile(item).toSeq.sortWith((u,v) => u._2 > v._2).map ( x => (x._1, x._2))
        .sortBy(_._1)
      for ( m <- map)
        output = output.concat(" " + (Integer.parseInt(m._1, 16)+1) + ":" + m._2)
      output
    })
    finalOutput.coalesce(1).saveAsTextFile(resultsPath + "Testing")
  }

  def convert (): Unit =
  {

    val data = sc.textFile(resultsPath + "/part-00000").take(1).map ( item => {
      val tokens = item.split(" ")
      val label = tokens(0)
      val features = tokens.drop(1).map ( x => (x.split(":")(0).toInt + 1+ ":" + x.split(":")(1)))
      var output = " "
      for (feature <- features)
        output = output + feature + " "
      label + output
    }).foreach(println)
  }

  def classifyRandomForest(): Unit = {
    val data = sqlContext.read.format("libsvm").load(resultsPath + "/part-00000")

    sc.textFile(resultsPath + "/part-00000").take(5).foreach(println)
    // Index labels, adding metadata to the label column.
    // Fit on whole dataset to include all labels in index.
    val labelIndexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("indexedLabel")
      .fit(data)

    // Automatically identify categorical features, and index them.
    // Set maxCategories so features with > 4 distinct values are treated as continuous.
    val featureIndexer = new VectorIndexer()
      .setInputCol("features")
      .setOutputCol("indexedFeatures")
      .setMaxCategories(40)
      .fit(data)


    // Split the data into training and test sets (30% held out for testing).
    val Array(trainingData, testData) = data.randomSplit(Array(0.9, 0.1))

    // Convert indexed labels back to original labels.
    val labelConverter = new IndexToString()
      .setInputCol("prediction")
      .setOutputCol("predictedLabel")
      .setLabels(labelIndexer.labels)

    // Train a RandomForest model.
    val rf = new RandomForestClassifier()
      .setMaxBins(40)
      .setLabelCol("indexedLabel")
      .setFeaturesCol("indexedFeatures")
      .setNumTrees(20)

    // Chain indexers and forest in a Pipeline.
    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, featureIndexer, rf, labelConverter))

    // Train model. This also runs the indexers.
    val model = pipeline.fit(trainingData)

    val predictions = model.transform(testData)

    // Select (prediction, true label) and compute test error.
    val evaluator = new MulticlassClassificationEvaluator()
      .setLabelCol("indexedLabel")
      .setPredictionCol("prediction")
      .setMetricName("precision")

    val accuracy = evaluator.evaluate(predictions)
    println("Test Error = " + (1.0 - accuracy))
    println("Accuracy = " + accuracy)
//
//    val rfModel = model.stages(2).asInstanceOf[RandomForestClassificationModel]
//    println("Learned classification forest model:\n" + rfModel.toDebugString)

//    val splits = data.randomSplit(Array(0.8, 0.2))
//    val (trainingData, testData) = (splits(0), splits(1))

//    val numClasses = 9
//    val categoricalFeaturesInfo = scala.Predef.Map[Int, Int]()
//    val numTrees = 3
//    val featureSubsetStrategy = "auto"
//
//    val impurity = "gini"
//    val maxDepth = 4
//    val maxBins = 4
//
//    val model = RandomForest.trainClassifier(data, numClasses, categoricalFeaturesInfo, numTrees, featureSubsetStrategy, impurity, maxDepth, maxBins)
//
//    val testingData = MLUtils.loadLibSVMFile(sc, resultsPath + "Testing")
//
//    testingData.map ( point => model.predict(point.features)).saveAsTextFile("ClassificationResult")

//    val labelAndPreds = testData.map { point =>
//      val prediction = model.predict(point.features)
//      (point.label, prediction)
//    }

//    val testErr = labelAndPreds.filter ( r => r._1 != r._2).count.toDouble / testData.count()
//    println("Test Error = " + testErr)
    //println("Learned classification forest model: \n" + model.toDebugString)

//    model.save(sc, "model")

  }
//
//  def classifyGBT(): Unit = {
//    // Load and parse the data file.
//    val data = MLUtils.loadLibSVMFile(sc, resultsPath + "/part-00000")
//    // Split the data into training and test sets (30% held out for testing)
//    val splits = data.randomSplit(Array(0.7, 0.3))
//    val (trainingData, testData) = (splits(0), splits(1))
//
//    // Train a GradientBoostedTrees model.
//    // The defaultParams for Classification use LogLoss by default.
//    var boostingStrategy = BoostingStrategy.defaultParams("Classification")
//    boostingStrategy.numIterations = 3 // Note: Use more iterations in practice.
//    boostingStrategy.treeStrategy.numClasses = 2
//    boostingStrategy.treeStrategy.maxDepth = 5
//    // Empty categoricalFeaturesInfo indicates all features are continuous.
//    boostingStrategy.treeStrategy.categoricalFeaturesInfo = scala.Predef.Map[Int, Int]()
//
//    val model = GradientBoostedTrees.train(trainingData, boostingStrategy)
//
//    // Evaluate model on test instances and compute test error
//    val labelAndPreds = testData.map { point =>
//      val prediction = model.predict(point.features)
//      (point.label, prediction)
//    }
//    val testErr = labelAndPreds.filter(r => r._1 != r._2).count.toDouble / testData.count()
//    println("Test Error = " + testErr)
//    println("Learned classification GBT model:\n" + model.toDebugString)
//
//    // Save and load model
//    model.save(sc, "target/tmp/myGradientBoostingClassificationModel")
//  }
}
