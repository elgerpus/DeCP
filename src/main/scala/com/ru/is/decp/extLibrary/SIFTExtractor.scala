package com.ru.is.decp.extLibrary

/**
 * Created by gylfi on 10/13/14.
 */
class SIFTExtractor() extends Serializable {
    System.loadLibrary("SIFTExtractor"); // Load dynamic lib<NAME>.jnilib linked in MacOs as -dynamiclib

  @native
  def sayHello

  @native
  def intMethod (n: Int): Int

  @native
  def booleanMethod(bool: Boolean): Boolean

  @native
  def stringMethod(text: String): String

  @native
  def intArrayMethod(intArray: Array[Int]): Int

  @native
  def calcSiftDescriptorsFromImage(width : Int, height : Int, maxVal : Int,
                                   step : Int, bin : Int, image : Array[Int]) : Int

  @native
  def calcAndGetDSIFTsFromImage(width: Int, height: Int, maxVal: Int,
                                step: Int, bin: Int, image: Array[Int]): Array[Short]

  @native
  def getMultiScaleDenseSIFTsFromPGMImageAsShorts(width: Int, height: Int, step: Int,
                                bin: Int, numScales: Int, image: Array[Int]): Array[Short]

  @native
  def getMultiScaleDenseSIFTsFromPGMImageAsFloats(width: Int, height: Int, step: Int,
                                                  bin: Int, numScales: Int, image: Array[Int]): Array[Float]

  @native
  def calcAndGetFVsFromImage(width: Int, height: Int, step: Int, bin: Int,
                               numScales: Int, image: Array[Int]): Array[Int]

  def main(args: Array[String]) {
    new SIFTExtractor().sayHello
  }

}
