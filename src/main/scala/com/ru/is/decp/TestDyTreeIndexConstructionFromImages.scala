package com.ru.is.decp

import java.io._
import java.util.Random

import com.ru.is.decp.extLibrary.boofcvlib

import scala.reflect.ClassTag

/**
 * @Author: Mae
 * @Date: 2020/12/25 12:27 下午
 */
object TestDyTreeIndexConstructionFromImages {

  def takeSample[T:ClassTag](a:Array[T],n:Int,seed:Long) = {
    val rnd = new Random(seed)
    Array.fill(n)(a(rnd.nextInt(a.size)))
  }

  def main(args: Array[String]): Unit = {

    if (args.length < 5) {
      println("Input parameters for usage are:")
      println( "< C (number of clusters)>, " +
        "< L (depth)>, " +
        "< treeA (soft-assignment in index)> " +
        "< OutputFileName (/path/name.ser)>, " +
        "< ImagesToExtractFrom (/path/images/)>" )
      sys.exit(2)
    }

    var start, end =  0L
    val booflib = new boofcvlib   // load the SIFT extractor library..
    var myTree = new DeCPDyTree   // create a new index structure
    var C = args(0).toInt
    var L =      args(1).toInt
    var treeA =  args(2).toInt

    val myTreeOutputFile = if (args(3).endsWith(".ser")) {
      args(3)
    } else {
      "DeCPDyTree_L"+L+"-treeA"+treeA+"-C"+C+".ser"
    }
    val objectOutputStream: ObjectOutputStream = new ObjectOutputStream( new FileOutputStream( myTreeOutputFile ) )

    val sifts = if (args(4).endsWith(".ser")) {
      val descInputStream = new ObjectInputStream(
        new BufferedInputStream(
          new FileInputStream( args(4) )
        )
      )
      descInputStream.readObject().asInstanceOf[Array[SiftDescriptorContainer]]
    } else {
      val descOutputStream: ObjectOutputStream = new ObjectOutputStream(
                                                    new FileOutputStream( "SiftDescCont.ser"))
      print("Starting to extract SIFTs from the images in " + args(4) +" and taking a random sample of size " + C)
      start = System.currentTimeMillis()
      // get an array of all the image File-handles
      val images = booflib.recursiveListJPGFiles( new java.io.File( args(4) ), ".jpg" )
      end = System.currentTimeMillis()
      val ret = booflib.getArrayOfSiftDescriptorContainersFromArrayOfFiles(images, 756.0F ).reduce((a,b) => a ++ b)
      println(" from " + images.length + " images and it took " + (end-start) )
      println(" Writing desc-containers to objectOutputStream")
      descOutputStream.writeObject(ret)
      descOutputStream.flush()
      descOutputStream.close()
      ret
    }

    val representatives = takeSample( sifts, C, System.currentTimeMillis() )
    var id = 0;
    for (desc <- representatives ) {
      desc.id = id
      id = id + 1;
    }

    println("We have random sampled " + representatives.length + " from " +
      + sifts.length  + " sifts as representatives from ")

    println("Building a "+myTree.L+" deep index with "+C+" clusters using "+myTree.treeA+" soft-assignment in index")
    start = System.currentTimeMillis()
    myTree.buildIndexTreeFromLeafs( L , treeA , representatives)
    System.gc()
    end = System.currentTimeMillis()
    println ("We just built an " + L + " level deep index with tree replication factor " + treeA + " and " +
      C + " clusters and it took " + (end - start) )

    println("Writing the new index, serialized to file, to the following path: " + myTreeOutputFile)
    start = System.currentTimeMillis()
    objectOutputStream.writeObject(myTree)
    objectOutputStream.flush()
    objectOutputStream.close()
    end = System.currentTimeMillis()
    println("Writing the object to file took: " + (end - start) )
  }
}
