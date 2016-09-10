import org.apache.spark.sql.SQLContext
/**
  * Created by UNisar on 9/2/2016.
  */
object main {

  def main (args: Array[String]): Unit =
  {
    var defaultAppName = "Test"
    var master = "local[8]"
    var memory = "14g"

    if (args.length < 6)
      {
        println ("Incorrect number of arguments. ")
        println ("Run as: run X_Train Y_Train X_Test Path_To_Binaries_Folder Output_Folder NumberOfGrams")
        sys.exit(1)
      }
    if (args.length > 6)
      {
        defaultAppName = args(6)
        master = args(7)
        memory = args(8)
      }
    driver.appName = defaultAppName
    driver.master = master
    driver.executor = memory
    driver.initialize()

    val d  = new driver(args{0}, args{1}, args{2}, args{3}, args{4}, args{5}.toInt)
//    d.run
//    d.convert
//    d.generateTest
      d.classifyRandomForest()

  }
}
