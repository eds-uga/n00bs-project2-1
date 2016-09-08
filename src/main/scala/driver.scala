import java.io.PrintWriter

import scala.collection.immutable.Vector
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.log4j.Logger
import org.apache.spark.mllib.feature.HashingTF
import org.apache.spark.mllib.tree.RandomForest
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.mllib.tree.GradientBoostedTrees
import org.apache.spark.mllib.tree.configuration.BoostingStrategy
import org.apache.spark.mllib.tree.model.GradientBoostedTreesModel
import org.apache.spark.mllib.util.MLUtils
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

//  def getNumeric(by: Vector[String]) =
//  {
//    var value = 0L;
//    for (int i = 0; i < by.length; i++)
//    {
//      value += ((long) by[i] & 0xffL) << (8 * i);
//    }
//  }
}
/**
  * Created by UNisar on 9/2/2016.
  */
class driver (xTrain: String, yTrain: String, xTest: String, binariesPath: String, resultsPath: String, numberOfGrams: Int) extends Serializable {
  import driver._

  val corpusIterator = sc.textFile(xTrain).zipWithIndex().map(c => (c._2, c._1)).cogroup(sc.textFile(yTrain).zipWithIndex().map(c => (c._2, c._1))).map ( x => (x._2._2.head, x._2._1.head))

  def readFile(inputPath: String) = {
    val fullPath = binariesPath + inputPath + ".bytes"
    sc.textFile(fullPath).
      map(x => x.split(" ").drop(1).
        sliding(numberOfGrams).map(_.mkString(""))).flatMap(c => c).countByValue().filter ( !_._1.forall(c => c.equals('?'))).
      filter (!_._1.forall ( c => c.equals('0')))
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
    val finalOutput = corpusIterator.map ( item =>
      {
        var output = (item._1.toInt - 1).toString
        val map: Seq[(String, Long)] = readFile(item._2).toSeq.sortWith((u,v) => u._2 > v._2).takeWhile(c => c._2 > 50).map ( x => (x._1, x._2))
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
      val map: Seq[(String, Long)] = readFile(item).toSeq.sortWith((u,v) => u._2 > v._2).takeWhile(c => c._2 > 50).map ( x => (x._1, x._2))
        .sortBy(_._1)
      for ( m <- map)
        output = output.concat(" " + (Integer.parseInt(m._1, 16)+1) + ":" + m._2)
      output
    })
    finalOutput.coalesce(1).saveAsTextFile(resultsPath + "Testing")
  }

  def convert(): Unit = {
    val inputFile = sc.textFile(resultsPath + "/part-00000")
    inputFile.map ( x => x.split(" ")).flatMap ( u => {
      val tf = new HashingTF(10000)
      var output = u(0)
      val without = u.drop(1)
      without.foreach ( value => {
        val keyvalue = value.split(":")
        output = output + " " + tf.indexOf(keyvalue(0)) + ":" + keyvalue(1)
      })
      Some(output)
    }).coalesce(1).saveAsTextFile("output")
  }

  def classifyRandomForest(): Unit = {
    val data = MLUtils.loadLibSVMFile(sc, resultsPath + "/part-00000")
//    val splits = data.randomSplit(Array(0.8, 0.2))
//    val (trainingData, testData) = (splits(0), splits(1))

    val numClasses = 9
    val categoricalFeaturesInfo = scala.Predef.Map[Int, Int]()
    val numTrees = 3
    val featureSubsetStrategy = "auto"

    val impurity = "gini"
    val maxDepth = 4
    val maxBins = 4

    val model = RandomForest.trainClassifier(data, numClasses, categoricalFeaturesInfo, numTrees, featureSubsetStrategy, impurity, maxDepth, maxBins)

    val testingData = MLUtils.loadLibSVMFile(sc, resultsPath + "Testing")

    testingData.map ( point => model.predict(point.features)).saveAsTextFile("ClassificationResult")

//    val labelAndPreds = testData.map { point =>
//      val prediction = model.predict(point.features)
//      (point.label, prediction)
//    }

//    val testErr = labelAndPreds.filter ( r => r._1 != r._2).count.toDouble / testData.count()
//    println("Test Error = " + testErr)
    //println("Learned classification forest model: \n" + model.toDebugString)

//    model.save(sc, "model")

  }

  def classifyGBT(): Unit = {
    // Load and parse the data file.
    val data = MLUtils.loadLibSVMFile(sc, resultsPath + "/part-00000")
    // Split the data into training and test sets (30% held out for testing)
    val splits = data.randomSplit(Array(0.7, 0.3))
    val (trainingData, testData) = (splits(0), splits(1))

    // Train a GradientBoostedTrees model.
    // The defaultParams for Classification use LogLoss by default.
    var boostingStrategy = BoostingStrategy.defaultParams("Classification")
    boostingStrategy.numIterations = 3 // Note: Use more iterations in practice.
    boostingStrategy.treeStrategy.numClasses = 2
    boostingStrategy.treeStrategy.maxDepth = 5
    // Empty categoricalFeaturesInfo indicates all features are continuous.
    boostingStrategy.treeStrategy.categoricalFeaturesInfo = scala.Predef.Map[Int, Int]()

    val model = GradientBoostedTrees.train(trainingData, boostingStrategy)

    // Evaluate model on test instances and compute test error
    val labelAndPreds = testData.map { point =>
      val prediction = model.predict(point.features)
      (point.label, prediction)
    }
    val testErr = labelAndPreds.filter(r => r._1 != r._2).count.toDouble / testData.count()
    println("Test Error = " + testErr)
    println("Learned classification GBT model:\n" + model.toDebugString)

    // Save and load model
    model.save(sc, "target/tmp/myGradientBoostingClassificationModel")
  }
}
