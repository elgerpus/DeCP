package pipelines

import java.io._

import eCP.Java.{SiftKnnContainer, SiftDescriptorContainer, eCPALTree}
import org.apache.hadoop.io.IntWritable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel



/**
 * Created by gylfi on 1/23/15.
 */
object DeCPPointScanning_clusterSelectionScore {
  def main(args: Array[String]): Unit = {
    println(args.length)
    if (args.length < 8) {
      println("Input parameters for usage are:")
      println("<SparkMaster> <SparkHome> <indexObjectFile> <datasetPath> <datasetFormat:0=SequenceFile;1=ObjectFile>" +
        "<QuerySetSequenceFile> <searchExpansion (b): 0=sequential scan> <resultDirectory>" +
        "<'optional' kNN size k: defaults to 20>")
      sys.exit(2)
    }

    // parse program arguments into variable-names that make some sense

    val sparkMaster = args(0) // local[4]
    val sparkHome = args(1) // /Code/spark-1.3.0
    val objectIndexFile = args(2) // /Code/Datasets/Index_C2000000_L4_treeA3.ser
    val dbFileName = args(3) // /Code/Datasets/db_100M_C2M_L4_treeA3_grouped_toArray_asObjectFile
    val dbFileFormat = args(4).toInt // 1
    val querysetPath = args(5) // /Code/Datasets/bigann_query.seq
    val searchExpansion = args(6).toInt // 1
    val resultdir = args(7) // /Code/Datasets/DeCPresults/run_b1/
    val k = if (args.length > 7) {
        args(8).toInt // 20
      } else {
        20
      }

    // create the spark context
    val conf = new SparkConf()
      .setMaster(sparkMaster)
      .setAppName("DeCPScanning")
      .setSparkHome(sparkHome)
      .setJars(SparkContext.jarOfObject(this).toSeq)
    //.set("spark.executor.memory", "1G")
    //.set("spark.driver.memory", "4G")
    //.set("spark.driver.maxResultSize", "0") // 0 = unlimited
    //.set("spark.shuffle.consolidateFiles", "true")
    //.set("spark.shuffle.spill", "true")
    //.set("spark.reducer.maxMbInFlight", "128")
    //.set("spark.shuffle.memoryFraction", "0.25")
    //.set("spark.shuffle.file.buffer.kb", "4096")
    //.set("spark.broadcast.blocksize", "16384")
    //.set("spark.shuffle.manager", "SORT")
    //.set("spark.files.fetchTimeout", "600")
    //.set("spark.local.dir", "/Volumes/MrRed/tmp")

    // create the Spark context
    //val sc = new SparkContext(sparkMaster, "DeCPScanning", sparkHome, SparkContext.jarOfObject(this).toSeq)
    val sc = new SparkContext(conf)
    println(sc.getConf.toDebugString)

    val querysetRDD = sc.sequenceFile(querysetPath, classOf[IntWritable], classOf[SiftDescriptorContainer])

    /* ### Scanning code #### */
    /* ## Searching with SearchExpansion  ####################################################### */
    /* #### LOADING THE INDEX ##### */
    val start_i = System.currentTimeMillis()
    //read the index from serialized file using an objectInputStream
    val objectInputStream_Index: ObjectInputStream =
      new ObjectInputStream(new FileInputStream(objectIndexFile))
    println("Loading the index")
    val myTree: eCPALTree = objectInputStream_Index.readObject().asInstanceOf[eCPALTree]
    objectInputStream_Index.close()
    val end_i = System.currentTimeMillis()
    println("Done loading the index and it took: " + (end_i - start_i))
    // DONE LOADING THE INDEX #### */

    // ### Query-point-to-Cluster discovery #####
    // brodcast the index
    println("Broadcasting the index")
    val myTreeBc = sc.broadcast(myTree)
    println("Done broadcasting the index")
    val start = System.currentTimeMillis()
    // create the cluster-to-query lookup-table        c2qLookup
    val a = querysetRDD.map(it => {
      val desc = new SiftDescriptorContainer()
      desc.id = it._2.id
      it._2.vector.copyToArray(desc.vector)
      (it._1.get(), desc)
    })
    //println(a.count())
    val b = a.map(tuple => {
      val searchExpansionFactor = searchExpansion + 1
      // getTopStaticTreeCluster() will return as many NN clusters as can be found,
      // i.e. this may be a value less then the requested number (searchExpansionFactor)
      val indexPath = tuple._1 + "|" +myTreeBc.value.getTopStaticTreeClusterPath(tuple._2, searchExpansionFactor)
      indexPath
    }).collect()
    for (str <- b) {
      print(str)
    }
    myTreeBc.unpersist(true)
    System.gc()

  }

}

// end of object
