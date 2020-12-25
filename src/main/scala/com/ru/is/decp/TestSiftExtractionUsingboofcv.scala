package com.ru.is.decp

import com.ru.is.decp.extLibrary.boofcvlib
import org.apache.spark.{SparkConf, SparkContext}

/**
 * Created by gylfi on 4/22/15.
 */
object TestSiftExtractionUsingboofcv {
  def main(args: Array[String]): Unit = {
    println(args.length)
    if (args.length < 3) {
      println("Input parameters for usage are:")
      println("<SparkMaster> <SparkHome> <pathToImageFolder> ")
      sys.exit(2)
    }
    // parse program arguments into variable-names that make some sense
    val sparkMaster = args(0) // local[4]
    val sparkHome = args(1) // /Code/spark-1.3.0
    val imagesFolder = new java.io.File(args(2)) // /Code/Datasets/INRIA_Images/
    if (! imagesFolder.isDirectory) {
      print("Path to images is not a folder")
      sys.exit(2)
    }

    /* #### create the spark context #### // */
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

    val booflib = new boofcvlib

    val imageFiles = booflib.recursiveListJPGFiles( imagesFolder, ".jpg" )
    println ( "There are " + imageFiles.length + " images in " + args(2) )

    val extractResults = booflib.getTimeAndSiftsAsByteArrayFromImageFiles(sc, imageFiles, true, 512)

    val numDesc = extractResults.map( _._2.length ).reduce( _+_ )
    val averageTime = extractResults.map( _._1._2).reduce( _+_ ) / imageFiles.length.toDouble

    println( "Num Desc total is " + numDesc)
    println( "Average # descriptors is " + numDesc/imageFiles.length.toDouble + " per image")
    println( "Average time per image is " + averageTime + "ms.")

    sys.exit(0)
  }
}
