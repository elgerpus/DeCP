# Distributed extended Cluster Pruning or DeCP 

A high-throughput CBIR system for very large image collections

## Features
* Deep hierarchical index construction, built top-down
* Approximate k-NN search 
  * Defaults to a single cluster but supports search-expansion as a run-time setting
* High-Throughput search 
  * Achieved by batching hundreds or thousands of queries into a single search
* End-to-end CBIR search engine 
	* Takes images (text file with paths) as input and prints out text-based ranked results (with paths to result images).
* Web-based API to visualize input and output is openly available
	* [DeCP-Live](http://github.com/elgerpus/DeCP-Live/)

## Getting Started

This version of DeCP does not have a built-in interface. We recomend using this as a back-end for the web-based inteface called DeCP-Live available [here](https://github.com/elgerpus/DeCP-Live/) or to download the the ready-to-go virtual machine with both DeCP and DeCP-Live pre-installed, available [here](https://link.to.vm).

## Syntax

DeCP Live uses a custom syntax for its input and output text files (the batch-query, batch-result and image results).
All files have in common a header line that has parameters used colon separated. All other lines of the files are paths to files, either images or other result files.  

* Query ("query".batch) 
  * The fields of the header line are "b : k : m", where b is the search expansion factor; k is the size of the k-nearest neighborhood; and m is the number of result images to keep for each query image.
  * The other n-lines are the paths to the query images for this batch.

* Batch result (batch.res)
  * For each batch a specific folder (directory) is created named after the "query".batch. In this folder a "batch.res" file is created that holds info on the batch search and links result files for each image in the batch. 
  * The fields of the header line in "batch.res" is "b : k : m : t", where b is the search expansion factor; k is the size of the k-nearest neighborhood used; m is the number of result images to keep for each query image; and t is the total time the search of this batch took in seconds. 
  * The other n-lines are paths to the result-file for each query image in the batch.

* Image result ( "imagename".res)
  * For each query image in a search batch a result file is created in the search batch folder. The header of this file holds "p:f" where p is the path to the query image and f is the number of SIFT features extracted from it.
  * The other m-lines are a ranked list of results. Each line is also colon separated lines:, the first value is the path to the result images and the second is the number of features that matched matched.

## Built With

* [Java 8](http://www.oracle.com/technetwork/java/javase/overview/index.html) - Programming language and run-time engine
* [Scala](https://www.scala-lang.org/) - Programming language 
* [BoofCV](https://boofcv.org/) - SIFT Feature Extraction
* [Spark](https://spark.apache.org/) - Distributed data processing
   

## Authors

* [Gylfi Þór Guðmundsson](http://www.ru.is/starfsfolk/gylfig/)
* [Laurent Amsaleg](http://people.rennes.inria.fr/Laurent.Amsaleg/)
* [Björn Þór Jónsson](https://www.ru.is/faculty/bjorn/)
* [Michael J. Franklin](https://cs.uchicago.edu/directory/michael-franklin/)

## Publications

* [Towards Engineering a Web-Scale Multimedia Service: A Case Study Using Spark](https://hal.inria.fr/hal-01416089/document) published in the proceedings of the 8th ACM conference on Multimedia Systems (MMSys17)

## License

This project is licensed under the [MIT licence](LICENSE.md)

## Acknowledgements

This work was supported in part by the Inria@SiliconValley program, DHS Award HSHQDC-16-3-00083, NSF CISE Expeditions Award CCF-1139158, DOE Award SN10040 DE-SC0012463, and DARPA XData Award FA8750-12-2-0331, and gifts from Amazon Web Services, Google, IBM, SAP, The Thomas and Stacey Siebel Foundation, Apple Inc., Arimo, Blue Goji, Bosch, Cisco, Cray, Cloudera, Ericsson, Facebook, Fujitsu, HP, Huawei, Intel, Microsoft, Mitre, Pivotal, Samsung, Schlum\-berger, Splunk, State Farm and VMware.
