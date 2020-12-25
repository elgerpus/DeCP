package com.ru.is.decp.pipelines

import java.io._
import com.ru.is.decp.{SiftKnnContainer, SiftDescriptorContainer, eCPALTree}
import org.apache.hadoop.io.IntWritable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel


/**
 * Created by gylfi on 1/23/15.
 */
object DeCPPointScanning {
  def dist( a: Array[Byte], b: Array[Byte]) : Int = {
    val dim = (if (a.length < b.length) { a.length } else {b.length} )
    var c = 0;
    var dist = 0;
    while (c < dim ) {
      dist += (a(c)-b(c)) * (a(c)-b(c))
      c = c + 1
    }
    return dist
  }
  def main(args: Array[String]): Unit = {
    println( args.length )
    if (args.length < 8) {
      println("Input parameters for usage are:")
      println("<SparkMaster> <SparkHome> <indexObjectFile> <datasetPath> <datasetFormat:0=SequenceFile;1=ObjectFile>" +
              "<QuerySetSequenceFile> <searchExpansion (b): 0=sequential scan> <resultDirectory>" +
              "<'optional' kNN size k: defaults to 20>")
      sys.exit(2)
    }
    // parse program arguments into variable-names that make some sense

    val sparkMaster = args(0)           // local[4]
    val sparkHome = args(1)             // /Code/spark-1.3.0
    val objectIndexFile = args(2)       // /Code/Datasets/Index_C2000000_L4_treeA3.ser
    val dbFileName = args(3)            // /Code/Datasets/db_100M_C2M_L4_treeA3_grouped_toArray_asObjectFile
    val dbFileFormat = args(4).toInt    // 1
    val querysetPath = args(5)          // /Code/Datasets/bigann_query.seq
    val searchExpansion = args(6).toInt // 1
    val resultdir = args(7)             // /Code/Datasets/DeCPresults/run_b1/
    val k = if (args.length > 8) {
      args(8).toInt                     // 20
    } else { 20 }

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

/* ###  Opening files ####
        We support databases both as SequenceFiles and ObjectFiles, However, SequenceFiles will be converted
        into a group-by-clusterID and data stored as array upon reading.    */
    val dbRDD = if (dbFileFormat == 0) {
      // Database is in SequenceFile format and thus not grouped by clusterID
      sc.sequenceFile(dbFileName, classOf[IntWritable], classOf[SiftDescriptorContainer]).map( it => {
        // first we map to avoid HDFS reader "re-use-of-writable" issue.
        val desc = new SiftDescriptorContainer()
        desc.id = it._2.id
        it._2.vector.copyToArray(desc.vector)
        (it._1.get(), desc)
      }).groupByKey().map( it => {
        // group clusters but we want them to be arrays, not iterable-once instances
        (it._1, it._2.toArray)
      }).persist(StorageLevel.DISK_ONLY)
    } else if (dbFileFormat == 1) {
      // Database is in ObjectFile format and thus already grouped by clusterID etc.
      sc.objectFile(dbFileName)
        .asInstanceOf[RDD[(Int, Array[SiftDescriptorContainer])]].persist(StorageLevel.DISK_ONLY)
    } else {
      println("The setting " + dbFileFormat + " as dataset file format is unrecognized")
      sys.exit(2)
    }
    val querysetRDD = sc.sequenceFile(querysetPath, classOf[IntWritable], classOf[SiftDescriptorContainer])
// Done opening files ##### */

/* ### Scanning code #### */

    // SEQUENTIAL SCANNING ####
    if (searchExpansion == 0) {
      // prepare the queryset
      val query  = querysetRDD.map( it => {
        val desc = new SiftDescriptorContainer()
        desc.id = it._2.id
        it._2.vector.copyToArray(desc.vector)
        (it._1.get(), desc)
      })
      // take one or more queries and broadcast them
      val bcqarr = sc.broadcast(query.take(10))
      //val bcqarr = sc.broadcast(query.collect())

      /* ### SeqScan using kNN and kNN reduction; Multi Query in batch ##############*/
      // first we map to avoid HDFS reader "re-use-of-writable" issue.
      val start = System.currentTimeMillis()
      val multiQscanknns = dbRDD.flatMap( clst => {
        var ret : List[(Int, SiftKnnContainer)] = Nil
        // knn init part
        val knns = bcqarr.value.map( qpair => {
          val knn = new SiftKnnContainer()
          knn.setK( k )
          knn.SetQueryPoint( qpair._2 )
          knn
        })
        // the scanning part
        for ( index <- 0 until knns.length) {
          for ( p <- clst._2) {
            knns(index).add(p)
          }
          ret = List( (index, knns(index)) ) ::: ret
        }
        ret
      })
      val knnsbyID = multiQscanknns.reduceByKey( (a,b) => SiftKnnContainer.mergeto1000(a,b) )
      val numRes = knnsbyID.map( pair => {
        println("Top " + pair._2.getK + " results for query " + pair._1)
        pair._2.sortKnnList()
        println( pair._2 )
      }).count()
      val end = System.currentTimeMillis()
      println("Queries in total: " + numRes + " and it took " + (end-start))
      // ### end of SeqScan using kNN; Multi Query in batch ##########*/
    }
/* ## Searching with SearchExpansion  ####################################################### */
    else {

      /* #### LOADING THE INDEX ##### */
      val start_i = System.currentTimeMillis()
      //read the index from serialized file using an objectInputStream
      val objectInputStream_Index: ObjectInputStream =
        new ObjectInputStream( new FileInputStream(objectIndexFile) )
      println( "Loading the index" )
      val myTree : eCPALTree = objectInputStream_Index.readObject().asInstanceOf[eCPALTree]
      val end_i = System.currentTimeMillis()
      println( "Done loading the index and it took: " + (end_i - start_i) )
      // DONE LOADING THE INDEX #### */

// ### Query-point-to-Cluster discovery #####
      // brodcast the index
      println("Broadcasting the index")
      val myTreeBc = sc.broadcast(myTree)
      println("Done broadcasting the index")
      val start = System.currentTimeMillis()
      // create the cluster-to-query lookup-table        c2qLookup
      val a = querysetRDD.map( it => {
        val desc = new SiftDescriptorContainer()
        desc.id = it._2.id
        it._2.vector.copyToArray(desc.vector)
        (it._1.get(), desc)
      })
      //println(a.count())
      val b = a.flatMap( tuple => {
        val searchExpansionFactor = searchExpansion
        // getTopStaticTreeCluster() will return as many NN clusters as can be found,
        // i.e. this may be a value less then the requested number (searchExpansionFactor)
        val clusters = myTreeBc.value.getTopStaticTreeCluster(tuple._2, searchExpansionFactor)
        //if( clusters.length < searchExpansionFactor) {
        //  println("only " + clusters.length + " out of " + searchExpansionFactor + " were found :(")
        //}
        var list : List[(Int, SiftDescriptorContainer)] = Nil
        // only loop over the number of items returned as they may be fewer than requested.
        for ( i <- 0 until clusters.length) {
          list = List( (clusters(i).id, tuple._2) ) ::: list
        }
        list
      })
      //println(b.count())
      val c = b.groupByKey()
      //println(c.count())
      val d = c.map( it => (it._1, it._2.toArray) )
      //println(d.count())
      // collect the queries as a map-structure
      val c2qLookup = d.collectAsMap()
      // once we have used the index we should do a clean-up before we proceed further
      myTreeBc.unpersist(true)
      System.gc()
      // broadcast the Query-to-Cluster lookupTable
      val c2qLookupBc = sc.broadcast(c2qLookup)
// ## DONE INDEXING WITH SOFT-ASSIGNMENT ####

/* ### DeCP search using Broadast of the C2Q #################################### */
      // Now we can filter the dataset to only include those clusters that are relevant
      val sub_objectDB_RDD = dbRDD.filter( c2qLookupBc.value contains _._1 )
      // and then we do a scan similar to the multi-SeqScan of the clusters
      val ClstScanKnns = sub_objectDB_RDD.flatMap( clst => {
        var ret : List[(Int, SiftKnnContainer)] = Nil
        if( c2qLookupBc.value contains clst._1 ) {
          val knns = c2qLookupBc.value(clst._1).map( qp => {
            val knn = new SiftKnnContainer()
            knn.setK( k )
            knn.SetQueryPoint( qp )
            knn
          })
          // the scanning part
          for ( k <- 0 until knns.length) {
            for ( p <- clst._2) {
              knns(k).add(p)
            }
            // create a tuple using the querypoint's ID as the key
            ret =  List( (knns(k).getQueryPoint.id , knns(k)) ) ::: ret
          }
        }
        ret
      })
      // group the knns by the querypoint IDs and merge the b kNNs for that qp
      val knnsbyID = ClstScanKnns.reduceByKey( (a,b) => SiftKnnContainer.mergeto1000(a,b) )
      // collect and print out result files, one per querypoint
      val results = knnsbyID.collect()
      for ( r <- results ) {
        val idstr = "000" + r._1.toString()
        val filename = idstr.substring(idstr.length-4,idstr.length)
        printf("Writing to: " + resultdir + filename + ".ecp")
        val file = new File (resultdir + filename + ".ecp")
        val bout = new BufferedWriter( new FileWriter(file) )
        val knn = r._2
        knn.sortKnnList()
        bout.write(knn.toString)
        bout.flush()
        bout.close()
      }
      val end = System.currentTimeMillis()

      println("Queries in total: " + knnsbyID.count() + " and it took " + (end-start))

      // ### end of DeCP search using Broadast of the C2Q ##########*/


      /* ### DeCP search using Join ########################################### * /
      // create the scan set by joining the database and the query set.
      val sub_objectDB_RDD = objectDB_RDD.filter( c2qLookupBc.value contains _._1 )

      val scansetRDD = sub_objectDB_RDD.join(d)

      val knnsetRDD = scansetRDD.flatMap( tuple => {
        var knnList : List[(Int, SiftKnnContainer)] = Nil
        knnList = {
          // Need to know which array is the queries and which is the cluster data.
          // For all the query points, create a k-nn and populate it by scanning the cluster data.
          for ( q <- tuple._2._2 ) {
            val knn: SiftKnnContainer = new SiftKnnContainer()
            knn.setK( k )
            knn.SetQueryPoint( q )
            // for all the points in the cluster, add them to the k-nn
            for ( p <- tuple._2._1 ) {
              knn.add(p)
            }
            knnList = List( (q.id, knn) ) ::: knnList
          }
          knnList
        }
        knnList
      })
      val knnsbyID  = knnsetRDD.reduceByKey( (a,b) => SiftKnnContainer.merge( a, b ) )
      val end = System.currentTimeMillis()

      var numRes = knnsbyID.map( pair => {
        println("Top " + pair._2.getK + " results for query " + pair._1)
        pair._2.sortKnnList()
        println( pair._2 )
      }).count()
      println("Queries in total: " + numRes + " and it took " + (end-start)) // * /
      println("Queries in total: " + knnsbyID.count() + " and it took " + (end-start))
      // */
      c2qLookupBc.unpersist(true)
      System.gc()
    } // end of else
  }// end of def main
} // end of object
