# DeCP

A high-throughput CBIR system for very large image collections

## Features
* Deep hierarchical index construction, built top-down
* Approximate k-NN search 
  * Default to a single cluster but supports search-expansion as a run-time setting
* High-Throughput search 
  * Achieved by batching hundreds or thousands of queries into a single search
* End-to-end CBIR search engine 
	* Takes images as input and prints out text-based result files. 
* Web-based API is openly available
	* [DeCP-Live](http://github.com/elgerpus/DeCP-Live/)

## Getting Started

This version of DeCP dows not have a built-in interface. We recomend using this as a back-end for the web-based inteface called DeCP-Live available [here](https://github.com/elgerpus/DeCP-Live/) or to download the the ready-to-go virtual machine with both DeCP and DeCP-Live pre-installed, available [here](https://link.to.vm).

## Syntax

DeCP Live uses a custom syntax for the query, batch result and image result files.

All files have in common a header line and other lines. The fields of the lines have a colon (:) as the field delimiter. 

### Query

The fields of the header line are b, k, number of results and number of images.

The other lines are absolute paths to the query images for this batch.

### Batch result

The fields of the header line are the same as the query header line with the addition of the total time the batch took.

The other lines are absolute paths to the individual image query results for this batch.

### Image result

The fields of the header line are the absolute path to the queried image and the number of features extracted from the image.

The other lines are absolute paths to the result images and the number of features matched.

## Built With

* [Java 8](http://www.oracle.com/technetwork/java/javase/overview/index.html) - Programming language and run-time engine
* [Scala](https://www.scala-lang.org/) - Programming language 
* [BoofCV](https://boofcv.org/) - SIFT Feature Extraction
* [Spark](https://spark.apache.org/) - Distributed data processing
   

## Authors

* [Gylfi Þór Guðmundsson](https://github.com/elgerpus)

## License

This project is licensed under the [MIT licence](LICENCE.md)

## Acknowledgements

This work was supported in part by the Inria@SiliconValley program, DHS Award HSHQDC-16-3-00083, NSF CISE Expeditions Award CCF-1139158, DOE Award SN10040 DE-SC0012463, and DARPA XData Award FA8750-12-2-0331, and gifts from Amazon Web Services, Google, IBM, SAP, The Thomas and Stacey Siebel Foundation, Apple Inc., Arimo, Blue Goji, Bosch, Cisco, Cray, Cloudera, Ericsson, Facebook, Fujitsu, HP, Huawei, Intel, Microsoft, Mitre, Pivotal, Samsung, Schlum\-berger, Splunk, State Farm and VMware.
