package com.ru.is.decp.pipelines

import java.io.{FileInputStream, ObjectInputStream}

import com.ru.is.decp.{SiftDescriptorContainer, eCPALTree}
import org.apache.hadoop.io.IntWritable
import org.apache.hadoop.conf._
import org.apache.hadoop.fs._
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark._
import org.apache.spark.rdd.{PairRDDFunctions, RDD, CoGroupedRDD}


/**
 * Created by gylfi on 1/22/15.
 */
object DeCPAssignments_DataInSequenceFile {
  def main(args: Array[String]): Unit = {
    if (args.length < 6) {
      println("Too few parameters:")
      println("<SparkMaster> <SparkHome> <IndexObjectFile> <RawdataSequenceFile> <OutputPath> <OutputFormat[0,1,2]>"
        + " <ReparationSize>")
      sys.exit(2)
    }
    // parse program arguments into variable-names that make some sense
    val sparkMaster = args(0)
    val sparkHome = args(1)
    val objectIndexFile = args(2)
    val siftDescriptorsFile_in = args(3) // "100M_bigann_base.seq"
    val outputFileName = args(4)
    // output format 0 = sequenceFile only; 1 = objectFile only; default = Both object- and sequenceFile are created.
    val outputFormat = args(5).toInt
    var numparts = 240
    if (args.length == 7) {
      numparts = args(6).toInt
    }
    println("Number of partitions is: " + numparts)

    // create the spark context
    val conf = new SparkConf()
      .setMaster(sparkMaster)
      .setAppName("DeCPAssignments")
      .setSparkHome(sparkHome)
      .setJars(SparkContext.jarOfObject(this).toSeq)
      //.set("spark.executor.memory", "1G")
      //.set("spark.driver.memory", "4G")
      //.set("spark.broadcast.blocksize", "16384")
      //.set("spark.files.fetchTimeout", "600")
      //.set("spark.worker.timeout","180")
      //.set("spark.driver.maxResultSize", "0") // 0 = unlimited
      //.set("spark.shuffle.manager", "SORT")
      //.set("spark.shuffle.consolidateFiles", "false")  // if true we have fetch failure, Kay says try without.
      //.set("spark.shuffle.spill", "true")
      //.set("spark.shuffle.file.buffer.kb", "1024")
      // because we use disk-only caching we reverse memoryFraction allocation.
      //.set("spark.shuffle.memoryFraction", "0.6")  // default 0.3
      //.set("spark.storage.memoryFraction","0.3")   // default 0.6
      //.set("spark.reducer.maxMbInFlight", "128")     // tried 128 and had issues.
      //.set("spark.akka.threads", "300")   // number of akka actors
      //.set("spark.akka.timeout", "180")   //
      //.set("spark.akka.frameSize", "10")  //
      //.set("spark.akka.batchSize", "30")  //
      //.set("spark.akka.askTimeout", "30") // supposedly this is important for high cpu/io load
      //.set("spark.local.dir", "/Volumes/MrRed/tmp")
    val sc = new SparkContext(conf)
    println(sc.getConf.toDebugString)

    //#### LOADING THE INDEX starts, the index is stored in an object file #####################

    println("Loding the index from object file")
    var start = System.currentTimeMillis()
    val objectInputStream: ObjectInputStream = new ObjectInputStream( new FileInputStream(objectIndexFile) )
    val myTree : eCPALTree = objectInputStream.readObject().asInstanceOf[eCPALTree]
    objectInputStream.close()
    var end = System.currentTimeMillis()
    println("loading the object took " + (end - start).toString() + "ms")
    println("The index has " + myTree.getNumberOfLeafClusters() + " clusters organized in an " + myTree.L +
      " deep hierarchy that uses a " + myTree.treeA + " folding replication strategy")
    //### DONE LOADING THE INDEX ##########################

    // ### CLUSTERING THE DATA, read raw data from sequence file cluster by traversing the index  ########
    // first we have to broadcast the index to all the participating nodes
    start = System.currentTimeMillis()
    val myTreeBc = sc.broadcast(myTree)
    end = System.currentTimeMillis()
    println("Broadcasting the index took " + (end-start) + "ms." )
    // then we load the raw data as an RDD

    val rawRDD = sc.sequenceFile(siftDescriptorsFile_in, classOf[IntWritable],
      classOf[SiftDescriptorContainer])
    // Repartition if we have to
    if (rawRDD.partitions.length < numparts ) {
      println("Repartitioning the input file as " + rawRDD.partitions.length + " < " + numparts )
      rawRDD.repartition(numparts)
      println("Number of partitions after repartitioning = " + rawRDD.partitions.length)
    }
    // optionally we can count the number of records to index but this requires one iteration over the data
    //println( "we are going to index :" + rawRDD.count() + " Descriptors")


    // single assignment is easy as it's a map() of 1-to-1, so we .map over the input RDD, calling the indexing
    val indexedRDD = rawRDD.map( it => {
      // this map is needed as the HDFS reader re-uses the writeable containers when reading.
      val desc = new SiftDescriptorContainer()
      desc.id = it._2.id
      it._2.vector.copyToArray(desc.vector)
      (it._1.get(), desc)
    }).map( pair => {
      val clusterID = myTreeBc.value.getTopStaticTreeCluster(pair._2, 1)(0).id
      //println(tuple._2.id + ": " + clusterID)
      (clusterID, pair._2)
    })
    //indexedRDD.persist(org.apache.spark.storage.StorageLevel.DISK_ONLY)
    // unload the index from the workers and free up the memory before we start shuffeling.
    myTreeBc.unpersist(true)
    System.gc()
    // ### DONE CLUSTERING THE DATA #######################

    // ### Now we need to group and/or sort the data into clusters and store it to disk ###############

    val dbFileUnsortedSeqName     = outputFileName + "_unsorted_asSequenceFile"
    val dbFileSortSeqName         = outputFileName + "_sorted_asSequenceFile"
    val dbFileObjName             = outputFileName + "_grouped_toArray_asObjectFile"

    if ( outputFormat == 0 ) {
      // alternative 0, only do a sortByKey + write to a sequencefile, arrays are not HDFS writeable by default.
      val indexedRDDsortedByKey = indexedRDD.sortByKey().map( pair => {
        val iwr = new IntWritable(pair._1)
        (iwr, pair._2)
      })
      indexedRDDsortedByKey.saveAsSequenceFile(dbFileSortSeqName)
    }
    else if ( outputFormat == 1 ) {
      // alternative 1, Store grouped data into object files,
      // first do a groupByKey, (optionally do a sort) and also change iterable to array + write to object file.
      val indexedRDDgroupedByKey = indexedRDD.groupByKey().map( pair => {
        (pair._1, pair._2.toArray)
      })
      indexedRDDgroupedByKey.saveAsObjectFile(dbFileObjName)
    }
    else {
      // Store both as unsorted, sortby and as groupby
      // as we will be doing two shuffles, we want to persist the initial assignments.
      indexedRDD.persist(org.apache.spark.storage.StorageLevel.DISK_ONLY)

      // unsorted sequence file, i.e. as-is
      indexedRDD.saveAsSequenceFile(dbFileUnsortedSeqName)

      // alt. 0, the sorted sequence file
      val indexedRDDsortedByKey = indexedRDD.sortByKey().map( pair => {
        val iwr = new IntWritable(pair._1)
        (iwr, pair._2)
      })
      indexedRDDsortedByKey.saveAsSequenceFile(dbFileSortSeqName)

      // alt. 1, a groupedByKey object file on HDFS.
      val indexedRDDgroupedByKey = indexedRDD.groupByKey().map( pair => {
        (pair._1, pair._2.toArray)
      })
      //indexedRDDgroupedByKey.repartition(numparts)
      indexedRDDgroupedByKey.saveAsObjectFile(dbFileObjName)

      // cleanup
      indexedRDD.unpersist(true)
    }
    System.gc()
    println("fin")
  }
}
