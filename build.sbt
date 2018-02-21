import AssemblyKeys._

assemblySettings

name := "DeCP"

retrieveManaged := true

version := "0.2"

organization := "is.ru"

scalaVersion := "2.11.4"

parallelExecution in Test := false

libraryDependencies ++= {
  Seq(
    "org.slf4j" % "slf4j-api" % "1.7.2",
    "org.slf4j" % "slf4j-log4j12" % "1.7.2",
    "org.scalatest" %% "scalatest" % "2.2.6" % "test",
    "org.apache.spark" % "spark-core_2.11" % "2.2.0",
    "org.apache.spark" % "spark-mllib_2.11" % "2.2.0",
    "org.apache.commons" % "commons-compress" % "1.7",
    "commons-io" % "commons-io" % "2.4",
    "org.boofcv" % "visualize" % "0.18",
    "org.scala-lang.modules" % "scala-xml_2.11" % "1.0.6"
  )
}

{
  val defaultHadoopVersion = "2.7.0"
  val hadoopVersion =
    scala.util.Properties.envOrElse("SPARK_HADOOP_VERSION",
defaultHadoopVersion)
  libraryDependencies += "org.apache.hadoop" % "hadoop-client" % hadoopVersion
}

resolvers ++= Seq(
  "Typesafe" at "http://repo.typesafe.com/typesafe/releases",
  "Spray" at "http://repo.spray.cc"
)

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
    {
      case PathList("javax", "servlet", xs @ _*)           => MergeStrategy.first
      case PathList(ps @ _*) if ps.last endsWith ".html"   => MergeStrategy.first
      case "application.conf"                              => MergeStrategy.concat
      case "reference.conf"                                => MergeStrategy.concat
      case "log4j.properties"                              => MergeStrategy.discard
      case m if m.toLowerCase.endsWith("manifest.mf")      => MergeStrategy.discard
      case m if m.toLowerCase.matches("meta-inf.*\\.sf$")  => MergeStrategy.discard
      case _ => MergeStrategy.first
    }
}

test in assembly := {}
