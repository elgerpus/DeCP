package pipelines

import java.io._

import eCP.Java.{SiftKnnContainer, SiftDescriptorContainer, eCPALTree}
import org.apache.hadoop.io.IntWritable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import scala.util.Random


/**
 * Created by gylfi on 1/23/15.
 */
object DeCPPointScanning_InputSetSplitting {
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
      args(8).toInt                     // defaults to 20
    } else { 20 }
    val MaxBatchSize = if (args.length > 9) {
      args(9).toInt                     // defaults to 1000
    } else { 1000 }
    val MaxResultSize = if (args.length > 10) {
      var ret = args(10).toInt          // defaults to 500
      // MaxResultSize should not be made larger then the size of the batch..
      if (ret > MaxBatchSize) {
        println("Setting the MaxResultSize lower then the MaxBatchSize does not make sense\n" +
          "MaxResultSize set equal to MaxResultSize")
        ret = MaxBatchSize
      }
      ret
    } else { 500 }

    // create the spark context
    val conf = new SparkConf()
      .setMaster(sparkMaster)
      .setAppName("DeCPPointScanning_InputSetSplitting")
      .setSparkHome(sparkHome)
      .setJars(SparkContext.jarOfObject(this).toSeq)
      //.set("spark.executor.memory", "1G")
      //.set("spark.driver.memory", "4G")
      //.set("spark.driver.maxResultSize", "2G") // 0 = unlimited
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
    dbRDD.setName("DeCP DB")
// Done opening files ##### */

/* ### Query initialization code #### */
    // need to define a constant of max-batch-size

    val querysetRDD = sc.sequenceFile(querysetPath, classOf[IntWritable],
                                      classOf[SiftDescriptorContainer], sc.getExecutorStorageStatus.length)
    .map( it => {
      val desc = new SiftDescriptorContainer()
      desc.id = it._2.id
      it._2.vector.copyToArray(desc.vector)
      (it._1.get(), desc)
    })
    querysetRDD.setName("querysetRDD")
    val querysetRDD_numPts = querysetRDD.count()
    val querysetRDD_numParts  = querysetRDD.partitions.length
    val querySetRDD_ptsPerPart = querysetRDD_numPts / querysetRDD_numParts
    // resize the partitions to have MaxBatchSize number of points.
    val repartto = (querysetRDD_numPts / MaxBatchSize).toInt
    val querysetRDD_repart = querysetRDD.repartition( repartto ).persist( StorageLevel.DISK_ONLY)
    querysetRDD_repart.setName("querysetRDD_repart")
    // force the repartition and caching of the input data.
    println( "querysetRDD_repart partitions: " + querysetRDD_repart.partitions.length +
      " and num pts: " + querysetRDD_repart.count() )
    // Loop through the partitions and get populate partRDD with only the data from one of them
    val parts = querysetRDD_repart.partitions
    for (p <- parts ) {
      val idx = p.index
      val partRDD = querysetRDD_repart.mapPartitionsWithIndex(
        (index: Int, it: Iterator[(Int, SiftDescriptorContainer)]) => {
        val ret = if (index == idx) {
          it
        } else {
          Iterator()
        }
        ret
      }, true).persist()
      // call the scanning code
      old_scan_code( sc, objectIndexFile, partRDD, searchExpansion, dbRDD, resultdir, k, MaxResultSize, idx)
      if (partRDD.getStorageLevel != StorageLevel.NONE) {
        partRDD.unpersist(true)
      }
    } // end for

    // delete the query-descriptor-set we forced to disk
    querysetRDD_repart.unpersist(true)

  }// end of def main

   /* ### Scanning code #### */
   def old_scan_code (sc: SparkContext,
                      objectIndexFile : String,
                      querysetRDD : RDD[(Int, SiftDescriptorContainer)],
                      searchExpansion : Int,
                      dbRDD : RDD[((Int, Array[SiftDescriptorContainer]))],
                      resultdir : String,
                      k : Int,
                      MaxResultSize : Int,
                      iteration: Int
                       ) : Unit = {
     /* ## Searching with SearchExpansion  ####################################################### */

     /* #### LOADING THE INDEX ##### */
     val start_i = System.currentTimeMillis ()
     //read the index from serialized file using an objectInputStream
     val objectInputStream_Index: ObjectInputStream =
       new ObjectInputStream (new FileInputStream (objectIndexFile) )
     println ("Loading the index")
     val myTree: eCPALTree = objectInputStream_Index.readObject ().asInstanceOf[eCPALTree]
     val end_i = System.currentTimeMillis ()
     println ("Done loading the index and it took: " + (end_i - start_i) )
     // DONE LOADING THE INDEX #### */

     // ### Query-point-to-Cluster discovery #####
     // brodcast the index
     println ("Broadcasting the index")
     val myTreeBc = sc.broadcast (myTree)
     println ("Done broadcasting the index")
     val start = System.currentTimeMillis ()
     // create the cluster-to-query lookup-table        c2qLookup
     val b = querysetRDD.flatMap (tuple => {
     val searchExpansionFactor = searchExpansion
     // getTopStaticTreeCluster() will return as many NN clusters as can be found,
     // i.e. this may be a value less then the requested number (searchExpansionFactor)
     val clusters = myTreeBc.value.getTopStaticTreeCluster (tuple._2, searchExpansionFactor)
     //if( clusters.length < searchExpansionFactor) {
     //  println("only " + clusters.length + " out of " + searchExpansionFactor + " were found :(")
     //}
     var list: List[(Int, SiftDescriptorContainer)] = Nil
     // only loop over the number of items returned as they may be fewer than requested.
     for (i <- 0 until clusters.length) {
     list = List ((clusters (i).id, tuple._2) ) ::: list
   }
     list
   })
     //println(b.count())
     val c = b.groupByKey ()
     //println(c.count())
     val d = c.map (it => (it._1, it._2.toArray) )
     //println(d.count())
     // collect the queries as a map-structure
     val c2qLookup = d.collectAsMap ()
     if (querysetRDD.getStorageLevel != StorageLevel.NONE ) {
       querysetRDD.unpersist( true )
     }
     // once we have used the index we should do a clean-up before we proceed further
     myTreeBc.unpersist (true)
     System.gc ()
     // broadcast the Query-to-Cluster lookupTable
     val c2qLookupBc = sc.broadcast (c2qLookup)
     // ## DONE INDEXING WITH SOFT-ASSIGNMENT ####

     /* ### DeCP search using Broadast of the C2Q #################################### */
     // Now we can filter the dataset to only include those clusters that are relevant
     val sub_objectDB_RDD = dbRDD.filter (c2qLookupBc.value contains _._1)
     // and then we do a scan similar to the multi-SeqScan of the clusters
     val kBc = sc.broadcast(k)
     val ClstScanKnns = sub_objectDB_RDD.flatMap (clst => {
       var ret: List[(Int, SiftKnnContainer)] = Nil
       if (c2qLookupBc.value contains clst._1) {
         val knns = c2qLookupBc.value (clst._1).map (qp => {
         val knn = new SiftKnnContainer (kBc.value)
         knn.SetQueryPoint (qp)
         knn
         })
         // the scanning part
         for (i <- 0 until knns.length) {
            for (p <- clst._2) {
              knns(i).add (p)
            } // end for (p <-
           // append the current k-NN to the output list
           //ret = List ((knns (i).getQueryPoint.id, knns (i) ) ) ::: ret
           ret = (knns (i).getQueryPoint.id, knns (i) ) :: ret
         } // end for (k <-
       } // end if
       ret
     }) // end flatMap on sub_objectDB_RDD

     // We now have multiple k-NNs, one for each b so we need to merge them into a single k-NN
     val knnsbyID = ClstScanKnns.reduceByKey ((a, b) => SiftKnnContainer.mergetosize(a, b, a.getK, true, true) )
     // flattent each RDD into a list of the <neighbourIDs, hub-ness count, max( distance)>
     val inversedknnsbyRDD = knnsbyID.flatMap( knn => {
       var ret: List[(Int, (Int, Int))] = Nil
       for (pair <- knn._2.getknnPairArray() ) {
         // the new tuple we output is of the form NN pointID, 1, distance
         //ret = List ( (pair.pointID, (1, pair.distance)) )
         if (pair.distance != Int.MaxValue ) {
           ret = (pair.pointID, (1, pair.distance)) :: ret
         }
       }
       ret
     }).persist( StorageLevel.DISK_ONLY)

     val resultRDD = inversedknnsbyRDD.reduceByKey( (a,b) => {
       ( (a._1 + b._1), math.max(a._2, b._2) )
     })

     // free up the disk space needed to cache-to-disk the inversed k-NNs
     inversedknnsbyRDD.unpersist(true)

     //* save the result to hdfs
     val storagePathResults = if (resultdir.endsWith("/")) {
       resultdir + iteration.toString() + "resultRDD"
     } else {
       "/" + resultdir + iteration.toString() + "resultRDD"
     }
     resultRDD.saveAsObjectFile( storagePathResults )
     // */

     //*  Save the k-NNs to disk (this is very large).
     val storagekNNs = if (resultdir.endsWith("/")) {
       resultdir + iteration.toString() + "knnRDD"
     } else {
       "/" + resultdir + iteration.toString() + "knnRDD"
     }
     knnsbyID.saveAsObjectFile( storagekNNs )
     // */

     val end = System.currentTimeMillis ()

     //println ("Queries in total: " + knnsbyID.count () + " and it took " + (end - start) )

     // ### end of DeCP search using Broadast of the C2Q ##########*/
     c2qLookupBc.unpersist (true)
     System.gc ()
   }

  /*
import org.apache.hadoop.io.IntWritable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

val mrdd = mergeHubRDDs (sc, "hdfs:///output/r100/", 0, 33)

   */
  def mergeHubRDDs ( sc : SparkContext, path : String, startVal : Int ,maxVal : Int  )
  : RDD[(Int, (Int, Int))] = {
    var i = startVal
    var repartsize = 400
    var rdd = sc.objectFile(path + startVal + "resultRDD").asInstanceOf[ RDD[(Int, (Int, Int))] ]
    rdd = rdd.repartition(repartsize).persist( StorageLevel.DISK_ONLY )
    for ( i <- (i+1) until maxVal ) {
      val old = rdd;
      val fullpath = path + i + "resultRDD"
      println("Reading RDD: " + fullpath)
      var t = sc.objectFile(fullpath).asInstanceOf[ RDD[(Int, (Int, Int))] ]
      t = t.repartition(repartsize).setName("t").persist()
      val nrdd = t.union(old).reduceByKey( (a,b) => {
        ( (a._1 + b._1), math.max(a._2, b._2) )
      })
      rdd = nrdd.repartition(repartsize).setName("rdd" + i ).persist( StorageLevel.DISK_ONLY )
      println("Reading RDD: " + fullpath)
      println("Rdd at " + i + " now has " + rdd.count() + " records")
      Thread.sleep(10000)
      t.unpersist( true )
      old.unpersist( true )
      sys.runtime.gc()
    }
    rdd
  }

/*
  val testing = mergeknnRDDs( sc, "/res100/", 0, 5)
  //var rdd = sc.objectFile("//").asInstanceOf[ RDD[(Int, SiftKnnContainer)] ]
// */
  def mergeknnRDDs( sc : SparkContext, path : String, startVal : Int ,maxVal : Int  )
    : RDD[(Int, SiftKnnContainer)] = {
    var i = startVal
    var repartsize = 400
    var rdd = sc.objectFile(path + startVal + "knnRDD").asInstanceOf[ RDD[(Int, SiftKnnContainer)] ]
    for ( i <- (i+1) until maxVal ) {
      val old = rdd;
      val fullpath = path + i + "knnRDD"
      var t = sc.objectFile(fullpath).asInstanceOf[ RDD[(Int, SiftKnnContainer)] ]
      t = t.repartition(repartsize).setName("t").persist()
      rdd = t.union(old).repartition(repartsize).persist( StorageLevel.DISK_ONLY )
      println("Reading knnRDD: " + fullpath)
      println("Merged RDD has " + rdd.count() + " + @ " + i )
      Thread.sleep(10000)
      old.unpersist( true )
      t.unpersist()
      sys.runtime.gc()
    }
    rdd
  }

  def findAndProcessNoHubnessScoringPointsInThe100M ( sc: SparkContext, rdd : RDD[(Int, (Int, Int) )]) : Unit = {
    // 1) generate a large amount random IDs within the range of IDs and store them as a Seq.
    val rand = new  scala.util.Random(System.currentTimeMillis())
    val range = 1000000000
    val randSeq : Seq[Int] = Seq.fill( 3000 )(rand.nextInt(range))
    // 2) use filterHubnessByArray() to filter the existing hubnessRDD using the generated Seq.
    val filteredRDD = filterHubnessByArray( sc, rdd, randSeq.toArray )
    // 3) map the filtered result to only contain the IDs and collect as a Seq.
    val filteredSet = filteredRDD.map( a => a._1 ).collect().toSet
    // 4) Subtract matched ID Seq. from the generated random Seq.
    val missingIDsArr : Array[Int] = ((randSeq.toSet) -- filteredSet).toArray
    // 5) Get the SiftDescriptorData for the missing IDs
    val missingSiftsRDD = extractSIFTsByArray( sc, "...", missingIDsArr )
    // 6) Query the DeCP search
    val k = 100
    val searchExpansion = 3
    val maxBatchSize = 3000000
    val indexPath = "/path-to-the-index.ser"
    val dbFileName = "hdfs:///HDFS-path-to-DBRDD"
    val resultFolder = "/path-to-folder-to-print-results"
    val dbRDD = sc.objectFile(dbFileName).asInstanceOf[RDD[(Int, Array[SiftDescriptorContainer])]]
    // the search code is impl. to write the results to disk .. :(
    old_scan_code( sc, indexPath, missingSiftsRDD, searchExpansion, dbRDD, resultFolder, k, maxBatchSize, 0 )
    // so we load it back up to an RDD from disk.
    val missingKnnsRDD = sc.objectFile(resultFolder + "0knnRDD").asInstanceOf[ RDD[(Int, SiftKnnContainer)] ]
    // 7) We now have the missing k-NNs and we want to add the hubness info for the NN-s from the 100M result.
    val hubnessRDDPath = "hdfs://path-to-hubness-RDD"
    var hubnessRDD = sc.objectFile(hubnessRDDPath).asInstanceOf[ RDD[(Int, (Int, Int))] ]

    val s1 = invertknn( sc, missingKnnsRDD )
    val s2 = s1.leftOuterJoin( hubnessRDD )
    val s3 = unOptionAnRDD( sc, s2 )

    val missingKnnWithHubnessArray = s3.collect()
    val missingKnnWIthHubnessRestultFile = "/Path-to-resultTextFile.txt"
    // 8) print the knn info ( the inverted k-NNs are inverted back in the print function).
    printInvertedKnnWithHubnessInfoRDDToFileAsText( missingKnnWithHubnessArray , missingKnnWIthHubnessRestultFile )

  }

  /**
   * Inverts the keyed RDD (queryID, SiftKnnContainer), where SiftKnnContainer has an Array of size k with
   * Array[(NNID, distance)] into an inverted RDD of ( NNID, (queryID, distance) )
   *
   * Note that a flatmap() is used as the invertedRDD returned will be much larger (k times larger) then the input RDD.
   * @param sc  SparkContext.
   * @param rdd A keyed RDD of k-NNs produced by a DeCP search.
   * @return  An RDD of all the k-NNs inverted.
   */
  def invertknn ( sc : SparkContext, rdd : RDD[(Int, SiftKnnContainer)])
  : RDD[(Int, (Int, Int))] = {
    val ret = rdd.flatMap( a => {
      var r : List[(Int,(Int, Int))] = Nil
      val knnpairs = a._2.getknnPairArray()
      for ( pair <- knnpairs ) {
        r = (pair.pointID, (a._1, pair.distance) ) :: r
      }
      r
    })
    ret
  }

  // hubRDD,  fullmergeknnRDD,  top100hubarr,  top100IDarr
  // val filtered = filterknnsByArray2( sc, fullmergeknnRDD, top100IDarr ).persist()
  /**
   * Filter the given keyed RDD of k-NNs such that only the k-NNs of the provided IDs are returned in a new keyed
   * k-NN RDD.
   * @param sc  SparkContext.
   * @param rdd A keyed RDD of k-NNs produced by a DeCP search.
   * @param IDs The set of keys to be found by way of filtering.
   * @return  A new keyed RDD with the containing the k-NNs of those IDs found in the input rdd and the IDs-Array.
   */
  def filterknnsByArray ( sc : SparkContext, rdd : RDD[(Int, SiftKnnContainer)], IDs : Array[Int])
  : RDD[(Int, SiftKnnContainer)] = {
    val ret = rdd.filter( IDs contains _._1  )
    ret
  }

  /**
   * Collect as an Array of hubness records the top X hubness records with the highest hubness-score from an hubnessRDD.
   * @param sc  SparkContext.
   * @param rdd The hubnessRDD, RDD[(ID: Int, (Hubness-Score : Int, Max(Dist.) : Int) )].
   * @param X The number of records to collect.
   * @return  The X records from rdd with the highest hubness-score as a sorted Array.
   */
  def collectTopXbyHubness(sc: SparkContext, rdd : RDD[(Int, (Int, Int) )], X : Int ) : Array[(Int, (Int, Int))] = {
    val ret = rdd
      .map( a => (a._2._1, (a._1, a._2._2)) ) // swap ID and hubness score (id, (H, max(D)) => (H, (id, max(D))
      .sortByKey(false)                     // sort by the H(ubbness) score Descending (highest first)
      .take(X)                                // collect the top X values
      .map( a => (a._2._1, (a._1, a._2._2)) ) // Swap back the ID and the hubness score.
    ret
  }

  /**
   * Print the a hubnessRDD to file as Text after collecting it first as an Array.
   * @param arr hubnessRDD information collected as an Array.
   * @param filename  Path to the file to write to.
   */
  def printHubnessInfoToFileAsText( arr : Array[(Int, (Int, Int))], filename : String ) : Unit = {
    val file = new BufferedWriter( new FileWriter( new File( filename )))
    arr.map( a => {
      file.write( a._1.toString)
      file.write(", ")
      file.write(a._2._1.toString)
      file.write(", ")
      file.write(a._2._2.toString)
      file.newLine()
    })
    file.flush()
    file.close()
  }

  /* Invertedknn is an RDD of format (Int, (Int, Int)) and so is the hubnessRDD and the printing function is used
   * like this:
   * printInvertedKnnWithHubnessInfoRDDToFileAsText( printableArray, " ... " )
   * where pritableArray is create with
   * val printableArray = invertedknn.join( hubnessRDD ).collect
   * ... OR ...
   * val printableArray = unOptionAnRDD( invertedknn.leftOuterJoin( hubRDD ) ).collect
   */

  /**
   * Print to file as text the un-inverted k-NN information after it has been joined with a hubnessRDD and collected,
   * i.e. the inverted k-NN info is un-inverted at print time. (queryID is the first number or the key in the text).
   * The Array info is as follows:
   * Array[( QID : Int, ( NNID : Int, Distance : Int), ( hubness-score : Int, max(Distance) : Int) )]
   *
   * Note that the the unOptionAnRDD() function can be used to produce a colectable RDD from the result of the
   * leftOuterJoin() between a invertedkNNRDD and the hubnessScoreRDD
   * @param arr The inverted kNN-with-hubnessinfo RDD.
   * @param filename  The path to the text file that should be written to.
   */
  def printInvertedKnnWithHubnessInfoRDDToFileAsText(
      arr : Array[(Int, ((Int, Int), (Int, Int)))],
      filename : String )
  : Unit = {
    val file = new BufferedWriter( new FileWriter( new File( filename )))
    arr.map( a => {
      // current format is inverted:
      // ( NNID, (QID, knn-D), (H, max-D) )
      // we print to the textfile un-inverted, i.e. ID first, then NNID.
      file.write(a._2._1._1.toString) // QID
      file.write(", ")
      file.write(a._1.toString)  // NNID
      file.write(", ")
      file.write(a._2._1._2.toString) // knn-D
      file.write(", ")
      file.write(a._2._2._1.toString) // Hubness of NNID
      file.write(", ")
      file.write(a._2._2._2.toString) // max-D of NNID
      file.newLine()
    })
    file.flush()
    file.close()
  }

  /**
   * Finds the maximum hub-score in the Hub-ness RDD
   * @param sc  Spark-Context
   * @param rdd The hubnessRDD, RDD[(ID: Int, (Hubness-Score : Int, Max(Dist.) : Int) )]
   * @return  The hubness-score corresponding to the maximum.
   */
  def maxHub ( sc: SparkContext, rdd : RDD[(Int, (Int, Int) )]) : Int = {
    val ret = rdd.reduce( (a, b) => {
      if ( a._2._1 > b._2._1) {
        a
      } else { b }
    })
    ret._2._1
  }

  /**
   * Find the hubness-score of the lowest ID in the given hubnessRDD.
   * @param sc  SparkContext.
   * @param rdd The hubnessRDD, RDD[(ID: Int, (Hubness-Score : Int, Max(Dist.) : Int) )]
   * @return  The hubness tuple, (ID : Int, (Hubness-score : Int, max(Dist.) : Int) ), of the lowest ID.
   */
  def minID ( sc : SparkContext, rdd : RDD[(Int, (Int, Int) )]) : ( Int, (Int, Int)) = {
    val ret = rdd.reduce( (a,b) => {
      if ( a._1 < b._1) {
        a
      } else { b }
    })
    ret
  }

  /**
   * Find the lowest value in the in the hubnessRDD using a reduce operation.
   * @param sc  SparkContext.
   * @param rdd The hubnessRDD, RDD[(ID: Int, (Hubness-Score : Int, Max(Dist.) : Int) )]
   * @return  The lowest observed hubness value.
   */
  def minHub ( sc: SparkContext, rdd : RDD[(Int, (Int, Int) )]) : Int = {
    val ret = rdd.reduce( (a, b) => {
      if ( a._2._1 < b._2._1) {
        a
      } else { b }
    })
    ret._2._1
  }

  /**
   * Sum the total hubness score in the given hubnessRDD.
   * @param sc  SparkContext.
   * @param rdd The hubnessRDD, RDD[(ID: Int, (Hubness-Score : Int, Max(Dist.) : Int) )]
   * @return  The sum of all the hubness-scores in the hubbnessRDD
   */
  def sumHub ( sc: SparkContext, rdd : RDD[(Int, (Int, Int) )]) : Long = {
    val rdd2 = rdd.map( a => ( a._1, (a._2._1.toLong, a._2._2)) )
    val ret = rdd2.reduce( (a, b) => {
      (0, (a._2._1 + b._2._1, 0))
    })
    ret._2._1
  }

  /**
   * Find how many points in a hubnessRDD have a given hubness-count.
   * @param sc  SparkContext.
   * @param rdd The hubnessRDD, RDD[(ID: Int, (Hubness-Score : Int, Max(Dist.) : Int) )]
   * @param value The hubness-score to look and count frequency for.
   * @return  The number of points with the given hubness-score.
   */
  def countHubEqualto ( sc: SparkContext, rdd : RDD[(Int, (Int, Int) )], value : Int) : Long = {
    val filter = sc.broadcast(value)
    val rdd2 = rdd.filter( (a) => a._2._1 == filter.value).map( a => 1L )
    val ret = rdd2.reduce( (a, b) => {
      a + b
    })
    filter.unpersist()
    ret
  }

  /**
   * Find the hubness-scores for the given IDs in an hubnessRDD.
   * @param sc  SparkContext.
   * @param rdd The hubnessRDD, RDD[(ID: Int, (Hubness-Score : Int, Max(Dist.) : Int) )]
   * @param IDs The set of IDs to search for.
   * @return  A filtered hubnessRDD containing only the hubness-socres for the IDs found.
   */
  def filterHubnessByArray ( sc : SparkContext, rdd : RDD[(Int, (Int, Int) )], IDs : Array[Int] )
  : RDD[(Int, (Int, Int) )] = {
    val ret = rdd.filter( IDs contains _._1  )
    ret
  }

  /**
   * Given a set of IDs, extractSIFTsByArray() will find and extract the IDs from a HDFS
   * sequenceFile[IntWritable,SiftDescriptorContainer] and return them as a key-value RDD.
   * @param sc  SparkContext.
   * @param querysetPath  The path to the HDFS sequence file that needs to be filtered.
   * @param IDs The IDs that should be filtered out.
   * @return  A keyed RDD containing the SiftDescriptorContainers identified by IDs found in the HDFS sequence file.
   */
  def extractSIFTsByArray (sc : SparkContext, querysetPath : String, IDs : Array[Int] )
  : RDD[(Int, SiftDescriptorContainer)] = {
    val querysetRDD = sc.sequenceFile(querysetPath, classOf[IntWritable],
      classOf[SiftDescriptorContainer], sc.getExecutorStorageStatus.length)
      .map( it => {
        val desc = new SiftDescriptorContainer()
        desc.id = it._2.id
        it._2.vector.copyToArray(desc.vector)
        (it._1.get(), desc)
    }).filter( IDs contains _._1 )
    querysetRDD
  }

  /**
   * Perform a sequential scan of a DeCP database dbRDD for the given set of query descriptors IDs.
   * Because this is a sequential scan the DeCP index is not needed.
   * For each cluster a k-NN is created and populated for each query vector in IDs, resulting in #IDs * #Clusters k-NNs.
   * All the k-NNs are then merged in a reducedByKey operations.
   * The result, a key-value paired RDD[(Int, SiftKnnContainer)] is then returned.
   * @param sc  SparkContext
   * @param dbRDD The DeCP-database RDD to sequentially scan
   * @param IDs Array of the query vectors with a query ID used as key.
   * @param k The size of the near neighbourhood to create
   * @return  An RDD of all the k-NNs created, keyed by the query ID.
   */
  def sequentialscanDB ( sc : SparkContext,
                         dbRDD : RDD[((Int, Array[SiftDescriptorContainer]))],
                         IDs : Array[(Int, SiftDescriptorContainer)],
                         k : Int
                       ) :  RDD[(Int, SiftKnnContainer)] = {

    val flatknns = dbRDD.flatMap( clst => {
      var ret : List[(Int, SiftKnnContainer)] = Nil
      // knn init part
      val knns = IDs.map( qpair => {
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
        ret = List( (knns(index).getQueryPoint().id, knns(index)) ) ::: ret
      }
      ret
    })
    val knnsbyID = flatknns.reduceByKey( (a,b) => SiftKnnContainer.mergetosize(a, b, k, false, true ) )
    knnsbyID
  }

  /**
   * Prints a key-value pair Array, typically collectd from an RDD, as text to file.
   * The key=queryID and value=SiftKnnContainer and a file is written to is given with the path.
   * @param arr Key-value Array consisting of (ID, SiftKnnContainer)
   * @param path  Path to file that should be written to.
   */
  def printknns ( arr : Array[(Int, SiftKnnContainer)], path : String) : Unit = {
    val file = new BufferedWriter( new FileWriter( new File( path )))
    arr.map( a => {
      // ( fkID, (ID, knn-D), (H, max-D) )
      file.write( a._1.toString ) // Hubness
      file.newLine()
      file.write( a._2.toString ) // max-D
      file.newLine()
    })
    file.flush()
    file.close()
  }

  /**
   * Given the output of a .leftOuterJoin between an inverted-kNN and a hubness-score result, i.e.
   * RDD[(Int, (Int, Int).leftOuterJoin( RDD[(Int, (Int, Int)) : RDD[(Int, ((Int, Int), Option[(Int, Int)]))],
   * the problem is that this can not be collected as an array and printed out.
   * If the key does not exist in the hubnessRDD the Optional part of the result will equal "None".
   * Thus, unOptionAnRDD() replaces any case where the option is None with a (0,0) value.
   * The output, RDD[(Int, ((Int, Int), (Int, Int)))], is and can be collected and printed out.
   * @param sc  SparkContext
   * @param rdd RDD created in the .leftOuterJoin() between two keyed RDDs of format RDD[(Int, (Int, Int))]
   * @return  RDD of format RDD[(Int, (Int, Int), (Int, Int))] that is collectable to the driver.
   */
  def unOptionAnRDD ( sc : SparkContext, rdd : RDD[(Int, ((Int, Int), Option[(Int, Int)]))] )
  : RDD[(Int, ((Int, Int), (Int, Int)))] = {
    val ret = rdd.map( a => {
      val p1 = a._1
      val p2 = a._2._1
      val p3 = if ( a._2._2 == None ) {
        (0,0)
      } else {
        a._2._2.get
      }
      (p1, (p2, p3))
    })
    ret
  }

}

// end of object
