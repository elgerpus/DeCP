package eCP

import java.io.{FileInputStream, ObjectInputStream}

import eCP.Java.{SiftKnnContainer, SiftDescriptorContainer, eCPALTree}
import org.apache.hadoop.io.IntWritable
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.rdd.{PairRDDFunctions, RDD, CoGroupedRDD}

import org.apache.spark.mllib.clustering.{KMeans, KMeansModel}
import org.apache.spark.mllib.linalg.Vectors

/**
 * Created by gylfi on 6/23/15.
 */
object GNDVectors_BigAnn10kQPset {

  def main(args: Array[String]): Unit = {
    val sparkMaster = args(0)
    val sparkHome = args(1)
    val sc = new SparkContext(sparkMaster, "Extract10kQPGNDVectors", sparkHome, SparkContext.jarOfObject(this).toSeq)

    /*
    val rdd = extract( sc, "/Code/Datasets/100M_gnd_info.txt", "/Code/Datasets/100M_bigann_base.seq")
    store ( rdd, "/Code/Datasets/10K_GNDvectors.ser")
    rdd.unpersist( true )
    // */

    val rdd2 = load(sc, "/Code/Datasets/10K_GNDvectors.ser").persist(StorageLevel.DISK_ONLY)
    //val c = rdd2.groupByKey().count()
    val kmModel = mlLibKmeans( sc, rdd2 )
    val kmModelbc = sc.broadcast(kmModel)
    val histogram = rdd2.map( record => {
      val doubleArray = record._2._2.vector.map( x => x.toDouble )
      val vector = Vectors.dense( doubleArray )
      val clusterID = kmModelbc.value.predict( vector )
      clusterID
    }).countByValue()
    kmModelbc.unpersist()
    println( histogram )

    rdd2.unpersist(true)
  }

def mlLibKmeans( sc : SparkContext , rdd : RDD[(Int, (Int, SiftDescriptorContainer))] ) : KMeansModel = {
  val rddOfVectors = rdd.map( record => {
    val doubleArray = record._2._2.vector.map( x => x.toDouble )
    Vectors.dense( doubleArray )
  }).persist()
  val clusters = KMeans.train( rddOfVectors, 200 , 4)
  rddOfVectors.unpersist(true)
  clusters
}


def load( sc : SparkContext, path : String ): RDD[(Int, (Int, SiftDescriptorContainer))] = {
  //val rdd : RDD[(Int, (Int, SiftDescriptorContainer))] = sc.objectFile( path )
  val rdd = sc.objectFile( path ).asInstanceOf[ RDD[(Int, (Int, SiftDescriptorContainer))] ]
  rdd
}

def store ( rdd : RDD[(Int, (Int, SiftDescriptorContainer))], outputPath : String  ): Boolean = {
  var ret : Boolean = true
  try {
    rdd.saveAsObjectFile(outputPath)
  } catch {
    case e : Exception => {
      e.printStackTrace()
      ret = false
    }
  }
  ret
}

def extract( sc : SparkContext, gndTextFile : String , rawDataSequenceFile : String )
  : RDD[(Int, (Int, SiftDescriptorContainer))] = {
    var c = 0L;
    val gndraw = sc.textFile(gndTextFile)
    //c = gndraw.count()
    val gndflt = gndraw.filter(!_.contains("1000 : 1000"))
    //c = gndflt.count()
    val gndasIntArray = gndflt.map(p => {
      val parts = p.split(":")
      val partsbin = parts.map(v => v.toInt)
      partsbin
    })
    val descID2queryID = gndasIntArray.map(arr => (arr(2), arr(0))).persist(StorageLevel.DISK_ONLY)
    //c = descID2queryID.count()
    val rawRDD = sc.sequenceFile(rawDataSequenceFile, classOf[IntWritable], classOf[SiftDescriptorContainer])
      .map(it => {
        val desc = new SiftDescriptorContainer()
        desc.id = it._2.id
        it._2.vector.copyToArray(desc.vector)
        (it._1.get(), desc)
      })
    val rawAndInfo = descID2queryID.join(rawRDD).persist(StorageLevel.DISK_ONLY)
    c = rawAndInfo.count()

    descID2queryID.unpersist(true)

    rawAndInfo
  }

}
