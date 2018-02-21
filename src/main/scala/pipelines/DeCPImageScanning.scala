package pipelines

import eCP.Java.{DeCPDyTree, SiftDescriptorContainer, SiftKnnContainer, eCPALTree}
import extLibrary.boofcvlib
import java.io._
import java.time.{Duration, Instant}

import org.apache.hadoop.io.IntWritable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.io.Source
import scala.collection.immutable.HashSet


/**
 * Created by gylfi on 1/23/15.
 * This is a demo-system for doing full image search, taking >=1 query images at a time.
 * It works by "dropping" images in a query folder, from there the images is moved to
 * a result folder with a result .html file that can be served by a http service.
 * This system supports two types of Databases, the classical DeCP that reads everything from DISK and the
 * Bag-of-Features in-memory variant proposed and described in our published work.
 *
 */
object DeCPImageScanning {

  def main(args: Array[String]): Unit = {

    println("Booting with " + args.length + " arguments")
    if (args.length < 8) {
      println("Input parameters for usage are:")
      println(
        "<SparkMaster>" +                          // args(0) -- local[4]
        "<SparkHome> " +                           // args(1) -- /Code/spark-2.2.0-bin-hadoop2.7
        "<indexObjectFile> " +                     // args(2) -- /Code/decp/DeCP/run/DeCPDyTree_L3-treeA3-C200000.ser
        "<pathToPreIndexedDataset> " +             // args(3) --  /Code/decp/DeCP/run/db
        "<datasetFormat:0=SequenceFile;1=ObjectFile>" + // args(4) -- 1
        "<QuerySetSource> " +                      // args(5) -- /Code/decp/DeCP/run/q/
        "<resultDirectoryRoot>" +                  // args(6) -- /Code/decp/DeCP/run/r/
        "<searchExpansion (default value)> " +     // args(7) -- 1
        "<kNN-size (default value)>" +             // args(8) -- 20
        "<ImagesToAddAtBoot>")                     // args(9) -- /Code/decp/DeCP/run/r/imgs/
      sys.exit(2)
    }
    // parse program arguments into variable-names that make some sense

    val sparkMaster = args(0) // local[4]
    val sparkHome = args(1) // /Code/spark-2.2.0-bin-hadoop2.7
    val objectIndexFile = args(2) // /Code/decp/DeCP/run/DeCPDyTree_L3-treeA3-C200000.ser
    val dbFileName = args(3) // /...
    val dbFileFormat = args(4).toInt // 1
    val queryPath = if (args(5).endsWith("/") ) { // /Code/decp/DeCP/run/q/
        args(5)
      } else {
        args(5) + "/"
      }
    val resultdir = args(6) // /Code/decp/DeCP/run/r/
    var searchExpansion = if ( args.length > 6 ) { // searchExpansion defaults to 1 (no expansion)
        args(7).toInt
      } else {
        1
      }
    var knnSize = if (args.length > 7) {  // knnSize defaults to 20
        args(8).toInt
      } else {
        20
      }
    var maxResults = 100
    val imagesInDB = if (args.length > 8) {  // Images that we want to add to the indexed DB
      if ( args(9).endsWith("/")) {
        args(9)
      } else {
        args(9) + "/"
      }
    } else {
      "/home/decp/images/dbimages/"
    }
    val imagesToAddToDB  = if (args.length > 9) {  // Images that we want to add to the indexed DB
      if (args(10).endsWith("/")) {
        args(10)
      } else {
        args(10) + "/"
      }
    } else {
      "/home/decp/images/addimages/"
    }



/* #### Create the spark context ########################### ########################### ########################### */
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

    val sc = new SparkContext(conf)
    println("############ SPARK CONFIGURATION STARTS ################")
    println(sc.getConf.toDebugString)
    println("############ SPARK CONFIGURATION ENDS ################")
/* #### Spark context created    ########################### ########################### ########################### */

/* #### Load the Index Structure ########################### ########################### ########################### */
    // Load the index structure, an instance of an index (eCPALTree/DeCPDyTree)
    // If we load a DB that is pre-built we do "preferably" want the same tree as was used to create said DB :)
    val myTree = loadIndexFromObjectFile( objectIndexFile )
/* #### Index Structure Loaded   ########################### ########################### ########################### */

/* #### Load SIFT ext. library   ########################### ########################### ########################### */
    val booflib = new boofcvlib // load the SIFT extractor library..
/* #### SIFT ext. library Loaded ########################### ########################### ########################### */

/* #### Find and Index Images    ########################### ########################### ########################### */
    val filesToAdd = booflib.recursiveListJPGFiles( new java.io.File( imagesToAddToDB ), ".jpg" ) //
    val partitionsToUse =
      if (filesToAdd.size > 1000) {  // set the partition size of the parallelized set of images to add
        filesToAdd.size / 500
      } else {
        4
      }
    // ask spark to distribute the image-set
    val imgToAddRDD = sc.parallelize( filesToAdd,  partitionsToUse )
    // extracting SIFTs using boofvc
    val descToAddRDD =
      booflib.getDescriptorUniqueIDAndSiftDescriptorsAsRDDfromRDDofImageFiles( sc, imgToAddRDD, true, imagesInDB)
    // index the SIFTs (using the DeCP-Index-hierarchy
    val toAddDBRDD = queryDescriptorAssignments( sc, myTree, descToAddRDD, 1 ).map( it => {
      // I need to remove the querypointID String as it is not needed, we only keep the clusterID as key
      val key = it._1;
      val value = (it._2).map( descPair => descPair._2 )
      (key, value)
    }).persist(StorageLevel.DISK_ONLY)
/* #### Indexing Images Done     ########################### ########################### ############################ */

/* #### Creating the DATABASE - Load-from-file and merge it with what we extracted from the images ################## */
    val dbRDD = if ( new File(dbFileName).exists() ) {
      println("Load the database from (Object)-file")
      val dbFromFileRDD = loadDB( sc, dbFileName, dbFileFormat )
      printf("dbFromFileRDD has " + dbFromFileRDD.count() + " and ")
      printf("toAddDBRDD has " + toAddDBRDD.count() )
      // make a supplemented dbRDD called dbRDD but we need to cache this one..
      println("Merging the loaded and extracted databases")
      this.leftOuterJoinTwoDBRDDs( dbFromFileRDD, toAddDBRDD )
    } else {
      toAddDBRDD
    }.persist(StorageLevel.DISK_ONLY)

    // unpersist the toAddDBRDD (as we don't need it anymore)
    toAddDBRDD.unpersist()

    // force db-creation at boot-time
    println( "dbRDD has " + dbRDD.count() + " clusters and ")
    //+ dbRDD.map( a => a._2.length ).reduce( (a,b) => a+b ) + " descriptors" )
/* #### Done creating the database #################################### #################################### #########*/

/* #### Create the BOF-version  ####################################### #################################### #########*/
    // make a BoF db from a dbRDD supplemented with the images
    val BoFDB = this.makeBoFDB( dbRDD ).cache()
    println("BoFDB has " + BoFDB.count() )
/* #### END OF ALL DATABASE CREATION ######################## ######################## ######################## ##### */

/* #### BEGINNING OF INFINITE SEARCH LOOP ############### ############### ############### ############### ########### */
    var run = true
    var sleepTimeFactor = 0.1
    while ( run ) {
      val queryBatchFiles = booflib.recursiveListJPGFiles( new java.io.File( queryPath ), ".batch" )
      if (queryBatchFiles.length == 0) {
        Thread.sleep( (sleepTimeFactor * 1000).toInt )
        if (sleepTimeFactor < 5.0) {
          sleepTimeFactor += 0.1
        }
      } else if ( queryBatchFiles.length == 1 & queryBatchFiles(0).getName.equals( "halt.batch" ) ) {
        // halting condition to terminate the program
        run = false
        queryBatchFiles(0).delete()
      } else if ( queryBatchFiles.length == 1 & queryBatchFiles(0).getName.equals( "save.batch" ) ) {
        // We have a request to save the current state of the database to file
        val reader = Source.fromFile(queryBatchFiles(0)).getLines()
        // check what type of file we should output to:
        if (reader.next() == "objectFile") {
          // write to an object file using the spark context (currently only supported format).
          val outputname = reader.next()
          println("Saving database to file to " + outputname)
          try {
            dbRDD.saveAsObjectFile( outputname )
          } catch {
            case e: Exception => {
              println("Exception caught trying to save databse to " + outputname) 
            }
          }
        } else {
          //TODO: Write out sequence as files or any other file format
          println("Saving Database failed as unknown file format was requested")
        }
        // delete the file for next iteration of the inf-loop
        queryBatchFiles(0).delete()
      } else {
/* ### Searching a batch of images  ################################################################################  */
        // messure how long it will take to run this batch, starting timer..
        val startTimer =  Instant.now()

        // stuff to search so we reset the sleep-timer in case we get more queries soon..
        sleepTimeFactor = 0.1
        //  Creating the querydescriptorset and partitioninig it if needed

        // parse the query.batch file to know what to do.
        // take the first batch file and open a scala Source to read it.
        val reader = Source.fromFile(queryBatchFiles(0)).getLines()
        // first line has the config info seperated by ':'
        val configFileds = reader.next().split(":")
        // check and set searchExpansion, knnSize and the maxResult size settings
        try {
          searchExpansion = configFileds(0).toInt
        } catch  {
          case e: Exception => {
            println("Exception caught trying to convert " + configFileds(0) + " to Int")
            searchExpansion = 1
          }
        }
        if (searchExpansion < 1 || searchExpansion > 10 ) {
          knnSize = 20
        }
        try {
          knnSize = configFileds(1).toInt
        } catch  {
          case e: Exception => {
            println("Exception caught trying to convert " + configFileds(1) + " to Int")
            searchExpansion = 1
          }
        }
        if (knnSize < 5 ) {
          knnSize = 20
        }
        try {
          maxResults = configFileds(2).toInt
        } catch  {
          case e: Exception => {println("Exception caught trying to convert " + configFileds(2) + " to Int")
            searchExpansion = 100
          }
        }
        if ( maxResults < 10 || maxResults > 1000 ) {
          println("Size of maxResult out of bounds (<10 or > 1000)")
          maxResults = 100
        }
        var queryFiles : List[File] = Nil
        while ( reader.hasNext ) {
          val line = reader.next()
          val ret = new File(line)
          if (ret.exists()) {
            queryFiles = List(ret) ::: queryFiles
          }
        }
        println(" and we read " + queryFiles.length + " valid image paths")
        // we have read the config and we have a list of Files with all the query images to parse
        // TODO: Check if queryFiles is empty and stop search if it is.

        val maxNumImagesBeforeMultiCore = 1000
        val numPart = if ( queryFiles.length > maxNumImagesBeforeMultiCore) {
          queryFiles.length / maxNumImagesBeforeMultiCore
        } else {
          1
        }
        // paralellize the query workload before extracting the features from images
        //val queryImgRDD = sc.parallelize( queryFiles, numPart ).randomSplit( Array.fill[Double]( numPart )( 1 ) )
/*
        // for each query image
        var  queryDescRDD : RDD[(String, SiftDescriptorContainer)] = null
        for ( queryImg <- queryImgRDD ) {
          // Extract the sifts, and we use the AbsoluteFilePath as the "key" with an "_descID" appended to it.
          val qdRDD = booflib.getDescriptorUniqueIDAndSiftDescriptorsAsRDDfromRDDofImageFiles(sc, queryImg)
          if ( queryDescRDD == null ) {
            queryDescRDD = qdRDD
          } else {
            queryDescRDD.++(qdRDD)
          }
        }
*/

        val queryImgRDD = sc.parallelize( queryFiles)
        val queryDescRDD = booflib.getDescriptorUniqueIDAndSiftDescriptorsAsRDDfromRDDofImageFiles(sc, queryImgRDD)

        // then I will use the joined db as the search db.
        // do the actual scanning and QP-b-merger and vote-aggregation
        val result = if (queryFiles(0).getName.startsWith("999") ) {
          // scan the in-memory Bag-of-Features variant of our Indexed-DB
          val queryRDDBOF = queryDescriptorAssignments(sc, myTree, queryDescRDD, 1)
          BoFscan( sc, BoFDB, queryRDDBOF, maxResults )
        } else {
          // scan the full descriptors, read from disk and all..
          val queryRDD = queryDescriptorAssignments(sc, myTree, queryDescRDD, searchExpansion)
          c2qCreationAndBroadcastScan( sc, myTree, dbRDD, queryRDD, knnSize, maxResults )
        }

/* #### Printing out the results #################################################################################### */
        // Done scanning so we print the results..
        // stop the timer ..
        val runningTime = Duration.between(startTimer, Instant.now()).getSeconds
        // We have a query batch with search settings ..
        // Then we have multiple search results, one per queryimage in the batch ..
        // create directory for results
        var batchDir = new File (resultdir +
          queryBatchFiles(0).getName.substring(0,queryBatchFiles(0).getName.lastIndexOf(".")))
        if (! batchDir.exists()) {
          batchDir.mkdir()
        } else {
          val tmpName = batchDir.getAbsolutePath() + "_" + System.currentTimeMillis().toString
          printf("batch with this name already exists, renameing to :" + tmpName)
          batchDir = new File( tmpName)
          batchDir.mkdir()
        }
        // write the batch result file
        val resFileBatch = new BufferedWriter( new FileWriter( batchDir.getAbsolutePath + "/batch.res" ) )
        // format of first line is "b:k:maxResultsPerQuery:#queryImagesInBatch:timeToProcessBatch:"
        resFileBatch.write( searchExpansion.toString + ":" + knnSize.toString  + ":" + maxResults.toString  + ":"
          +result.length.toString + ":" + runningTime.toString + ":\n" )
        resFileBatch.flush()
        println("printing" + result.length + " results to result directory " + resultdir)
        for (r <- result) {
          // make the File handle for the result file
          val idstr = r._1
          val filenameFullPath = batchDir.getAbsolutePath + "/" + idstr.replaceAll("/","#") + ".res"
          println("Writing to: " + filenameFullPath)
          val file = new File( filenameFullPath )
          val bout = new BufferedWriter( new FileWriter( file ) )
          // the resultstring has first a single line with the number of SIFTs extraced from the query-image, then
          // each line that follows is a ranked list of databas-image-id and the number of votes it got
          val lines = r._2.split("\n") // get all the lins of the result-string
          // For our output we want the path to the query-image, followed by a colon and the number of SIFTs
          bout.write(idstr + ":" + lines(0).toInt + ":\n")
          for (line <- lines) {
            // the database image ID and the number of votes is seperated by a single space (' ')
            val words = line.split(" ")
            if (words.length > 2) {  // skip the first line that only had the number of SIFTs
              // TODO: HERE WE NEED TO REPLACE THE db-IIDs with the absoulute path and stop using the imagesToAddDB path
              // Shit mix fix until we do the TO-DO above is the hard-code the path
              bout.write( imagesInDB +  words(1) + ".jpg" + ":" + words(2) + ":\n")
            }
          }
          bout.flush()
          bout.close()
          // add the result file to the batchResult as a seperate line
          resFileBatch.write(filenameFullPath + "\n")
          resFileBatch.flush()
        } // end for (r <- result)

        println("Batch of " + result.length + " done and written to file")

        // delete the processed batch file
        queryBatchFiles(0).delete()
      } // end else (of the if not spin-locking, waiting for queries
/* #### Done printing the search results ############################################################################ */
    } // end while
    BoFDB.unpersist(true)
    dbRDD.unpersist(true)
    println("fin")
    sys.exit(0)
  }// end main

  /**
   * Create a .html file style file to display results..
   * This function assumes the image-file source is in a fixed sub-directory called ./imgs/
   * and that all the image-files in that sub-directory are named the same as the numeric DB-imageID
   *
   * @param id  Query-image-id
   * @param result Result as lines of plain text (not HTML)
   * @return Results as HTML formatted text
   */
  def resultToHTMLConverter( id: String, result : String ) : String = {
    // result is of the format "Int(numdesc)\n\nInt(rowNum)\tINT(id)\tVote

    val htmlbuilder = new StringBuilder()
    htmlbuilder.append("<HTML>\n<HEAD>\n\t<TITLE>Result for " + id + "</TITLE>\n<HEAD>\n<BODY>\n")
    val lines = result.split("\n")
    htmlbuilder.append("<h2><a href='/queries/'>Queries</a>&nbsp &nbsp <a href='/results'>Results</a></h2>\n")
    htmlbuilder.append("<h2>Results for query " + id + " (" + lines(0).toInt + " descriptors) <br>")
    htmlbuilder.append("\t<div style=\"width: 200px; height:200px; padding: 5px;border: margin: 0px;\">\n")
    htmlbuilder.append("\t\t<a href='" + id + "'>\n")
    htmlbuilder.append("\t\t\t<img src='" + id + "' style='max-width: 95%;max-height: 95%;'>\n")
    htmlbuilder.append("\t\t</a>\n\t</div>\n")
    htmlbuilder.append("</h2>\n<hr>\n")
    for (line <- lines) {
      val words = line.split(" ")
      var c = 0
      if (words.length > 2) {
        htmlbuilder.append("\n<div style=\"width:200px;height:200px;padding: 5px;border: margin: 0px;" +
          "float:left;align:center;vertical-align: middle;\">\n")
        htmlbuilder.append("\tImage " + words(1) + " with " + words(2) + " votes<br>\n")
        htmlbuilder.append("\t<a href='./imgs/" + words(1) + ".jpg'>")
        htmlbuilder.append("<img src='./imgs/" + words(1) + ".jpg' style='max-width: 95%;max-height: 95%;'></a>\n")
        htmlbuilder.append("</div>\n")
      }
    }
    htmlbuilder.append("<div style=\"width: 210px; height:210px; padding: 5px;border: margin: 0px;float:left;" +
      "align: center; vertical-align: middle;\">\n\t<br><br>Thats all folks!\n</div>")
    htmlbuilder.append("</BODY></HTML>")
    htmlbuilder.toString()
  }

  /**
   * Loads an eCPALTree index from object file give as input path
   *
   * @param objectIndexFile Path to the eCPALTree as object file to load
   * @return Returns a populated instance of eCPALTree
   */
  //def loadIndexFromObjectFile( objectIndexFile: String ) : eCPALTree = {
  def loadIndexFromObjectFile( objectIndexFile: String ) : DeCPDyTree = {
    val start_i = System.currentTimeMillis()
    //read the index from serialized file using an objectInputStream
    val objectInputStream_Index: ObjectInputStream =
      new ObjectInputStream(new FileInputStream(objectIndexFile))
    println("Loading the index")
    //val myTree: eCPALTree = objectInputStream_Index.readObject().asInstanceOf[eCPALTree]
    var myTree : DeCPDyTree = objectInputStream_Index.readObject().asInstanceOf[DeCPDyTree]
    val end_i = System.currentTimeMillis()
    println("Done loading the index and it took: " + (end_i - start_i) + "ms.")
    println("Index has " + myTree.getNumberOfLeafClusters + " clusters in a " + myTree.L + " layers")
    myTree
  }// end loadIndexFromOBjectFile

  /**
   * Loads SIFT-descriptor query files from directory
   * @param querysetPath Path to the directory with the query files
   * @return Returns a List key-value pairs <String, SiftDescriptorContainer> where the key is the combined query-image
   *         and point ID and the value is the SiftDescriptor (query image ID + SIFT vector)
   */
  def parseQueriesFromSiftFiles(querysetPath : String) : List[(String,SiftDescriptorContainer)] = {
    /* ######## Load the image queries // */
    val start_q = System.currentTimeMillis()
    var descriptors = List[(String, SiftDescriptorContainer)]()
    val queryfiles = new java.io.File(querysetPath)
    descriptors = if (!queryfiles.isDirectory) {
      print("The querysetPath provided is not a directory")
      sys.exit(2);
    }
    else {
      println("There are " + queryfiles.listFiles().size + " input files")
      val files = queryfiles.listFiles()
      for (f <- files) {
        if (f.length() % 128 != 0) {
          println(f.getName + " size is not a multiple of 128, file is skipped")
        } else {
          // open the file as a buffered input stream
          val bf = new BufferedInputStream(new FileInputStream(f))
          val numdims = (f.length() / 128).toInt
          for (i <- 0 until numdims) {
            val fname = f.getName()
            // use the file-name as the image-query-ID
            var id = fname.substring(0, fname.indexOf('.'))
            val desc = new SiftDescriptorContainer(id.toInt)
            bf.read(desc.vector)
            descriptors = List((id + "_" + i, desc)) ::: descriptors
          }
          // close buffered input stream of the file
          bf.close()
        }
      }
      // return all the descriptors read from all the input files
      descriptors
    }// end else
    val end_q = System.currentTimeMillis()
    println(descriptors.length + " query descriptors loaded from " + queryfiles.listFiles().length +
      " files in " + (end_q - start_q) + "ms.")
    descriptors
  } // end parseQueriesFromFiles

  /**
   * Create a DB RDD from SequenceFile or ObjectFile using Spark Context.
   *
   * @param sc The Spark Context
   * @param dbFileName Path to the indexed DB to load as an RDD
   * @param dbFileFormat Integer indicating the file type: 0 for SequenceFileFile; 1 for ObjectFile
   * @return Paired RDD of the format RDD[(Int, Array[SiftDescriptorContainer])] where the key is the cluster ID and
   *         the array is the SIFT descriptors assigned to the cluster
   */
  def loadDB( sc : SparkContext, dbFileName: String, dbFileFormat : Int )
  : RDD[(Int, Array[SiftDescriptorContainer])] = {
    /* ###  Opening files ####
      We support databases both as SequenceFiles and ObjectFiles, However, SequenceFiles will be converted
      into a group-by-clusterID and data stored as array upon reading.    */
    val dbRDD = if (dbFileFormat == 0) {
      // Database is in SequenceFile format and thus not grouped by clusterID
      sc.sequenceFile(dbFileName, classOf[IntWritable], classOf[SiftDescriptorContainer]).map(it => {
        // first we map to avoid HDFS reader "re-use-of-writable" issue.
        val desc = new SiftDescriptorContainer()
        desc.id = it._2.id
        it._2.vector.copyToArray(desc.vector)
        (it._1.get(), desc)
      })
        // group clusters but we want them to be arrays, not iterable-once instances
        .groupByKey().map(it => (it._1, it._2.toArray) )
    } else if (dbFileFormat == 1) {
      // Database is in ObjectFile format and thus already grouped by clusterID etc.
      sc.objectFile(dbFileName).asInstanceOf[RDD[(Int, Array[SiftDescriptorContainer])]]
    } else {
      println("The setting " + dbFileFormat + " as dataset file format is unrecognized")
      sys.exit(2)
    }
    dbRDD
  } // end loadDB

  /**
   * This function scans the database to produce results for the given query batch.
   * First step: A Cluster-to-Query lookup table is created by collecting the queryRDD
   * Second step: The C2Q table is broadcasted
   * Third step: The database RDD is scanned by using the C2Q table, creating b * QP k-NNs
   * Forth step: Merge the b k-NNs per query point using a reduceByKey and re-key the result imageID
   * Fifth step: VoteAggregate all the k-NNs for a given ImageID, again using a reduceByKey
   *
   * @param sc  The Spark Context
   * @param myTree  The eCPALTree, loaded and/or populated
   * @param dbRDD The clustered and grouped by clusterID database of high-dimensional vectors
   * @param queryRDD The query batch RDD that we can collect and make the C2Q lookup table from.
   *                    value the high-dimensional vector. The vector ID is however the query imageID.
   * @param k The number of near-neighbours to keep for each query-point scanned.
   * @param maxResults The maximum number of database images to keep after the vote-aggregation
   * @return  Returns key-value pair where the key is the query image ID and the value is results for that image.
   *          I.e. the result per image is a string of the maxResult most similar database images.
   *          Please not that the result string is in ascending order and thus the most similar images will be last.
   */
  def c2qCreationAndBroadcastScan ( sc        : SparkContext,
                                    myTree    : eCPALTree,
                                    dbRDD     : RDD[(Int, Array[SiftDescriptorContainer])],
                                    queryRDD  : RDD[(Int, Array[(String, SiftDescriptorContainer)])],
                                    k         : Int,
                                    maxResults: Int) : Array[(Int, String)] = {
    // start by collecting the querydata into a single map that we can broadcast

    queryRDD.cache()
    val numQPs = queryRDD.map( a => a._2.length).reduce( (a,b) => a+b)
    val numClust = queryRDD.count()
    println( numClust.toString() + " Unique clusters : " + numQPs.toString + " unique queries" )
    val c2qLookup = queryRDD.collectAsMap()
    queryRDD.unpersist(true)

    // broadcast the Query-to-Cluster lookupTable
    val start_c2q = System.currentTimeMillis()
    val c2qLookupBc = sc.broadcast(c2qLookup)
    val end_c2q = System.currentTimeMillis()
    println("Done broadcasting the c2qLookupTable, it took " + (end_c2q - start_c2q) + "ms.")
    val maxResultsbc = sc.broadcast(maxResults)
    val kbc = sc.broadcast(k)
    val shortlist = dbRDD
      .filter( c2qLookupBc.value contains _._1)
      .flatMap( clst => {
      var ret : List[(String, SiftKnnContainer)] = Nil
      if( c2qLookupBc.value contains clst._1 ) {
        val knns = c2qLookupBc.value(clst._1).map( qp => {
          val knn = new SiftKnnContainer()
          knn.setK( kbc.value )
          knn.SetQueryPoint( qp._2 )
          ( qp._1, knn )
        })
        // the scanning part
        for ( i <- 0 until knns.length) {
          for ( p <- clst._2) {
            knns(i)._2.add(p)
          }
          ret =  List( knns(i) )  ::: ret
        }
      }
      ret
    })
     //*  prevent double counting imageIDs, even if b=1
      .map( a => {
        val ret = new SiftKnnContainer(a._2.getK)
        ret.SetQueryPoint(a._2.getQueryPoint)
        a._2.getknnPairArray().map( knnpair => ret.addNoDuplicateIDs(knnpair.pointID, knnpair.distance))
        (a._1, ret)
    }) // */
    //println( " b " + b.count)
    //val c = b
      // done scanning all b clusters so we have b k-NNs for each Qp, need to merge ot 1 k-NN per Qp
      .reduceByKey( (a,b) => SiftKnnContainer.mergetosizeoflarger(a,b) )
      // done reducing back to one k-NN per Qp, now change key to be ImageID
    //val d = c
      .map( pair => (pair._2.getQueryPoint().id, pair._2) )
    //println( " d " + d.count)
      // VoteAggregate all the k-NNs for each query point by reducing again by key
    //val e = d
      .reduceByKey( (a,b) => SiftKnnContainer.voteAggregate(a,b))
    //println( " e " + e.count)
      // the result-NNs are now super long, so we cut them down in size to a max value
    //val f = e
      .map( a => (a._1, SiftKnnContainer.shortlist(a._2, maxResultsbc.value, false).toString)).collect()
    //println( " f " + f.length)
    //val shortlist = f
    maxResultsbc.unpersist(true)
    kbc.unpersist(true)
    return shortlist
  } // end scan

  /**
   * This function scans the database to produce results for the given query batch.
   * First step: A Cluster-to-Query lookup table is created by collecting the queryRDD
   * Second step: The C2Q table is broadcasted
   * Third step: The database RDD is scanned by using the C2Q table, creating b * QP k-NNs
   * Forth step: Merge the b k-NNs per query point using a reduceByKey and re-key the result imageID
   * Fifth step: VoteAggregate all the k-NNs for a given ImageID, again using a reduceByKey
   *
   * @param sc  The Spark Context
   * @param myTree  The DeCPDyTree, loaded and/or populated
   * @param dbRDD The clustered and grouped by clusterID database of high-dimensional vectors
   * @param queryRDD The query batch RDD that we can collect and make the C2Q lookup table from.
   *                    value the high-dimensional vector. The vector ID is however the query imageID.
   * @param k The number of near-neighbours to keep for each query-point scanned.
   * @param maxResults The maximum number of database images to keep after the vote-aggregation
   * @return  Returns key-value pair where the key is the query image ID and the value is results for that image.
   *          I.e. the result per image is a string of the maxResult most similar database images.
   *          Please not that the result string is in ascending order and thus the most similar images will be last.
   */
  def c2qCreationAndBroadcastScan ( sc        : SparkContext,
                                    myTree    : DeCPDyTree,
                                    dbRDD     : RDD[(Int, Array[SiftDescriptorContainer])],
                                    queryRDD  : RDD[(Int, Array[(String, SiftDescriptorContainer)])],
                                    k         : Int,
                                    maxResults: Int) : Array[(String, String)] = {
    // start by collecting the querydata into a single map that we can broadcast

    queryRDD.cache()
    val numQPs = queryRDD.map( a => a._2.length).reduce( (a,b) => a+b)
    val numClust = queryRDD.count()
    println( numClust.toString() + " Unique clusters : " + numQPs.toString + " unique queries" )
    val c2qLookup = queryRDD.collectAsMap()
    queryRDD.unpersist(true)

    // broadcast the Query-to-Cluster lookupTable
    val start_c2q = System.currentTimeMillis()
    val c2qLookupBc = sc.broadcast(c2qLookup)
    val end_c2q = System.currentTimeMillis()
    println("Done broadcasting the c2qLookupTable, it took " + (end_c2q - start_c2q) + "ms.")
    val maxResultsbc = sc.broadcast(maxResults)
    val kbc = sc.broadcast(k)
    val shortlist = dbRDD
      .filter( c2qLookupBc.value contains _._1)
      .flatMap( clst => {
      var ret : List[(String, SiftKnnContainer)] = Nil
      if( c2qLookupBc.value contains clst._1 ) {
        val knns = c2qLookupBc.value(clst._1).map( qp => {
          val knn = new SiftKnnContainer()
          knn.setK( kbc.value )
          knn.SetQueryPoint( qp._2 )
          ( qp._1, knn )
        })
        // the scanning part
        for ( i <- 0 until knns.length) {
          for ( p <- clst._2) {
            knns(i)._2.add(p)
          }
          ret =  List( knns(i) )  ::: ret
        }
      }
      ret
    })
      //*  prevent double counting imageIDs, even if b=1
      .map( a => {
        val ret = new SiftKnnContainer(a._2.getK)
        ret.SetQueryPoint(a._2.getQueryPoint)
        a._2.getknnPairArray().map( knnpair => ret.addNoDuplicateIDs(knnpair.pointID, knnpair.distance))
        (a._1, ret)
       }) // */
      //println( " b " + b.count)
      //val c = b
      // done scanning all b clusters so we have b k-NNs for each Qp, need to merge to 1 k-NN per Qp
      .reduceByKey( (a,b) => SiftKnnContainer.mergetosizeoflarger(a,b) )
      // done reducing back to one k-NN per Qp, now change key to be ImageID
      //val d = c
        .map( pair => {
          (pair._1.substring(0, pair._1.lastIndexOf('_') ), pair._2)
        })
      //.map( pair => (pair._2.getQueryPoint().id, pair._2) )
      //println( " d " + d.count)
      // VoteAggregate all the k-NNs for each query point by reducing again by key
      //val e = d
      .reduceByKey( (a,b) => SiftKnnContainer.voteAggregate(a,b))
      //println( " e " + e.count)
      // the result-NNs are now super long, so we cut them down in size to a max value
      //val f = e
      .map( a => (a._1, SiftKnnContainer.shortlist(a._2, maxResultsbc.value, false).toString)).collect()
    //println( " f " + f.length)
    //val shortlist = f
    maxResultsbc.unpersist(true)
    kbc.unpersist(true)
    return shortlist
  } // end scan


  /**
   * Index descriptors extracted from images using a DeCP index and make it run in parallel on Spark
   *
   * @param sc The Spark Context
   * @param myTree The DeCP index structure to use (eCPALTree)
   * @param descriptors Array of descriptors to index, each stored in a SiftDescriptorContainer
   * @param searchExpansion The number of top-k clusters to keep track of for each query descriptor
   * @return Returns an distributed RDD file where each element in RDD is a tuple of assignment id and input descriptor
   */
  def queryDescriptorAssignments (  sc          : SparkContext,
                                    myTree      : eCPALTree,
                                    descriptors : Seq[(String, SiftDescriptorContainer)],
                                    searchExpansion : Int )
  : RDD[(Int, Array[(String, SiftDescriptorContainer)])] = {
    val querysetRDD = sc.parallelize(descriptors)
    queryDescriptorAssignments(sc, myTree, querysetRDD, searchExpansion)
  }

  /**
   * Index an already distributed RDD of descriptors, extracted from images, using a DeCP index
   *
   * @param sc The Spark Context
   * @param myTree The DeCP index structure to use (eCPALTree)
   * @param querysetRDD query descriptors as an distributed RDD
   * @param searchExpansion The number of top-k clusters to keep track of for each query descriptor
   * @return
   */
  def queryDescriptorAssignments (  sc          : SparkContext,
                                    myTree      : eCPALTree,
                                    querysetRDD : RDD[(String, SiftDescriptorContainer)],
                                    searchExpansion : Int )
  : RDD[(Int, Array[(String, SiftDescriptorContainer)])] = {
    //#### Build the Cluster-2-Query Lookup Table using Spark cluster and broadcasting the index. ###########
    // First we broadcast the index
    ///TODO: Broacast the index only once to a different Spark Cluster and leave it on the workers.
    val start_d = System.currentTimeMillis()
    println("Broadcasting the index")
    val myTreeBc = sc.broadcast(myTree)
    val end_d = System.currentTimeMillis()
    println("Done broadcasting the index, it took " + (end_d - start_d) + "ms.")
    // Create an RDD from the queries

    // create the cluster-to-query lookup-table        c2qLookup
    val c2qLookup = querysetRDD.flatMap(tuple => {
      val searchExpansionFactor = searchExpansion
      // getTopStaticTreeCluster() will return as many NN clusters as can be found,
      // i.e. this may be a value less then the requested number (searchExpansionFactor)
      val clusters = myTreeBc.value.getTopStaticTreeCluster(tuple._2, searchExpansionFactor)
      //if( clusters.length < searchExpansionFactor) {
      //  println("only " + clusters.length + " out of " + searchExpansionFactor + " were found :(")
      //}
      var list: List[(Int, (String, SiftDescriptorContainer))] = Nil
      // only loop over the number of items returned as they may be fewer than requested.
      for (i <- 0 until clusters.length) {
        list = List((clusters(i).id, tuple)) ::: list
        //list = list :::  List((clusters(i).id, tuple))
      }
      list
    }) .groupByKey().map( it => (it._1, it._2.toArray) )
    // once we have used the index we should do a clean-up before we proceed further
    println("Done with assignments, unloading the broadcasted index")
    myTreeBc.unpersist(true)
    sys.runtime.gc()
    c2qLookup
  }


  /**
   * Index descriptors extracted from images using a DeCP index and make it run in parallel on Spark
   *
   * @param sc The Spark Context
   * @param myTree The DeCP index structure to use (DeCPDyTree)
   * @param descriptors query descriptors as an in-local-memory Seq-Array
   * @param searchExpansion The number of top-k clusters to keep track of for each query descriptor
   * @return
   */
  def queryDescriptorAssignments ( sc           : SparkContext,
                                   myTree       : DeCPDyTree,
                                   descriptors  : Seq[(String, SiftDescriptorContainer)],
                                   searchExpansion : Int ) : RDD[(Int, Array[(String, SiftDescriptorContainer)])] = {
    val querysetRDD = sc.parallelize(descriptors)
    queryDescriptorAssignments(sc, myTree, querysetRDD, searchExpansion)
  }

  /**
   * Index an already distributed RDD of descriptors, extracted from images, using a DeCP index
   *
   * @param sc The Spark Context
   * @param myTree The DeCP index structure to use (DeCPDyTree)
   * @param querysetRDD query descriptors as an distributed RDD
   * @param searchExpansion The number of top-k clusters to keep track of for each query descriptor
   * @return
   */
  def queryDescriptorAssignments ( sc           : SparkContext,
                                   myTree       : DeCPDyTree,
                                   querysetRDD  : RDD[(String, SiftDescriptorContainer)],
                                   searchExpansion : Int ) : RDD[(Int, Array[(String, SiftDescriptorContainer)])] = {
    //#### Build the Cluster-2-Query Lookup Table using Spark cluster and broadcasting the index. ###########
    // First we broadcast the index
    ///TODO: Broacast the index only once to a different Spark Cluster and leave it on the workers.
    val start_d = System.currentTimeMillis()
    println("Broadcasting the index")
    val myTreeBc = sc.broadcast(myTree)
    val end_d = System.currentTimeMillis()
    println("Done broadcasting the index, it took " + (end_d - start_d) + "ms.")
    // Create an RDD from the queries

    // create the cluster-to-query lookup-table        c2qLookup
    val c2qLookup = querysetRDD.flatMap(tuple => {
      val searchExpansionFactor = searchExpansion
      // getTopStaticTreeCluster() will return as many NN clusters as can be found,
      // i.e. this may be a value less then the requested number (searchExpansionFactor)
      val clusters = myTreeBc.value.getTopStaticTreeCluster(tuple._2, searchExpansionFactor)
      //if( clusters.length < searchExpansionFactor) {
      //  println("only " + clusters.length + " out of " + searchExpansionFactor + " were found :(")
      //}
      var list: List[(Int, (String, SiftDescriptorContainer))] = Nil
      // only loop over the number of items returned as they may be fewer than requested.
      for (i <- 0 until clusters.length) {
        list = List((clusters(i).id, tuple)) ::: list
        //list = list :::  List((clusters(i).id, tuple))
      }
      list
    }) .groupByKey().map( it => (it._1, it._2.toArray) )
    // once we have used the index we should do a clean-up before we proceed further
    println("Done with assignments, unloading the broadcasted index")
    myTreeBc.unpersist(true)
    sys.runtime.gc()
    c2qLookup
  }

  /**
   * Takes two key-value pair RDDs of the format (Int, Array) joins them by concatenating the Arrays
   * @param dbRDD The first RDD
   * @param addition The second RDD
   * @return the two RDDs joined, and the value Arrays merged
   */
  def joinTwoDBRDDs ( dbRDD : RDD[(Int, Array[SiftDescriptorContainer])],
                      addition : RDD[(Int, Array[SiftDescriptorContainer])]
                    ) : RDD[(Int, Array[SiftDescriptorContainer])] = {
    if (dbRDD.count() > 0) {
      if (addition.count() == 0 ) {
        return dbRDD
      }
      val ret = dbRDD
        .join(addition)
        .map( a => {
          val id = a._1
          val arr = a._2._1 ++ a._2._2
          (id, arr)
        })
      return ret
    }
    else {
      return addition
    }
  }

  /**
   * This is the default function to merge two indexed data-sets (RDD's) using a leftOuterJoin()
   *
   * @param dbRDD The previous (larger) RDD of indexed and clustered features
   * @param addition The (smaller) indexed and clusters RDD of data to be merged to the (larger) dbRDD
   * @return Returned is a new RDD, post-merger, containing the content of both input RDDs
   */
  def leftOuterJoinTwoDBRDDs( dbRDD : RDD[(Int, Array[SiftDescriptorContainer])],
                              addition : RDD[(Int, Array[SiftDescriptorContainer])]
                            ) : RDD[(Int, Array[SiftDescriptorContainer])] = {
    if (dbRDD.count() > 0) {
      if (addition.count() == 0 ) {
        return dbRDD
      }
      val ret = dbRDD.leftOuterJoin(addition)
        .map( a => {
        val id = a._1
        val arr =
        if ( a._2._2 == None) { // there may have been no new data for this cluster
          a._2._1
        } else { // if there is new data for this cluster we append it (merge the two arrays).
          a._2._1 ++ a._2._2.get
        }
        (id, arr)  // for each cluster we return either the same data as was in dbRDD or the new merged version
      })
      return ret
    }
    else {
      return addition
    }
  }

  /**
   * Construct an imitation of the well know Bag-of-Features algorithm from an indexed DeCP clustering by removing the
   * actual high-dimensional data from the features stored in DeCP and replacing the cluster data with a single k-NN
   * that is ready to be vote-aggregated. Because we retain only the ID's the resulting data-set is much smaller and
   * thus it can then be stored (cached) in-memory instead of being stored on secondary storage (DeCP default).
   * At search time, instead of scanning the cluster for the queries k-NN neighbours the BoF search gets a copy of the
   * clusters pre-populated k-NN that is ready for vote-aggregation.
   *
   * @param dbRDD Indexed and clustered DeCP data-set to transform into a BoF data-set.
   * @return Returns an indexed and searchable data-set where the high-dimensional vector data has been stripped out
   */
  def makeBoFDB ( dbRDD : RDD[(Int, Array[SiftDescriptorContainer])] ) : RDD[(Int, SiftKnnContainer)]= {
    // Create the BOF DB by changing each cluster into a k-NN with the descriptor content.
    val bofDB = dbRDD.map( a => {
      val clstrData = a._2
      val knn = new SiftKnnContainer(clstrData.length)
      // calling halt scan indicates to the search process that the k-NN contains vote-count values,
      // not distance values, and is therefor ready for Aggregation. Thus, BoF-search will never scan, only aggregate.
      knn.haltScan()
      val allowDuplicate = false
      if (allowDuplicate) {
        for (p <- clstrData) {
          knn.add(p.id, 1)
        } // end for
      } else {
        var chk = new HashSet[Int]()
        for (p <- clstrData) {
          if(! chk.contains( p.id) ) {
            knn.add( p.id , 1 )
            chk += p.id
          }
        }// end for
      }// end else
      ( a._1, knn )
    })
    bofDB
  }

  /**
   * At search time, instead of scanning the cluster for the queries k-NN neighbours the BoF search gets a copy of the
   * clusters pre-populated k-NN that is ready for vote-aggregation.
   *
   * @param sc The Spark Context
   * @param dbRDD RDD of pre-populated k-NN's, one per cluster.
   * @param queryRDD RDD of the query vectors to search
   * @param maxResults
   * @return
   */
  def BoFscan (sc : SparkContext,
               dbRDD : RDD[(Int, SiftKnnContainer)],
               queryRDD : RDD[(Int, Array[(String, SiftDescriptorContainer)])],
               maxResults: Int) : Array[(String, String)] =  {

    // start by collecting the querydata into a single map that we can broadcast
    val c2qLookup = queryRDD.collectAsMap()
    val numqps = c2qLookup.map( a => a._2.length).reduce( (a,b) => a+b)
    println( c2qLookup.size.toString() + " Unique clusters : " + numqps + " unique queries" )
    // broadcast the Query-to-Cluster lookupTable
    val start_c2q = System.currentTimeMillis()
    val c2qLookupBc = sc.broadcast(c2qLookup)
    val end_c2q = System.currentTimeMillis()
    println("Done broadcasting the c2qLookupTable, it took " + (end_c2q - start_c2q) + "ms.")
    val maxResultsbc = sc.broadcast(maxResults)

    val shortlist = dbRDD
      .filter( c2qLookupBc.value contains _._1)
      .flatMap(clstknn => {
      var ret : List[(String, SiftKnnContainer)] = Nil
      if( c2qLookupBc.value contains clstknn._1 ) {
        val knns = c2qLookupBc.value(clstknn._1).map( qp => {
          val knn = new SiftKnnContainer()
          knn.setK( clstknn._2.getK )
          knn.SetQueryPoint( qp._2 )
          (qp._1, knn)
        })
        for ( i <- knns) {
          val clusterknnpairs =  clstknn._2.getknnPairArray()
          for (pair <- clusterknnpairs) {
            //i._2.addNoDuplicateIDs(pair.pointID, pair.distance)
            i._2.add(pair.pointID, pair.distance)
          }
          ret =  List( i )  ::: ret
        }
      }
      ret
    })
      // done scanning all b clusters so we have b k-NNs for each Qp, need to merge ot 1 k-NN per Qp
      .reduceByKey( (a,b) => SiftKnnContainer.mergetosizeofboth(a,b) )
      // done reducing back to one k-NN per Qp, now change key to be ImageID
        .map( pair => (pair._1.substring(0, pair._1.lastIndexOf('_')), pair._2) )
      //.map( pair => (pair._2.getQueryPoint().id, pair._2) )
      // VoteAggregate all the k-NNs for each query point by reducing again by key
      .reduceByKey( (a,b) => SiftKnnContainer.voteAggregate(a,b) )
      // the result-NNs are now super long, so we cut them down in size to a max value
      .map( a => (a._1, SiftKnnContainer.shortlist(a._2, maxResultsbc.value, false).toString)).collect()
    c2qLookupBc.unpersist(true)
    maxResultsbc.unpersist(true)
    return shortlist
  }
} // end object
