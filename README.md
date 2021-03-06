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

## Getting Started

This version of DeCP does not have a built-in interface. We recomend using this as a back-end for the web-based inteface called DeCP-Live available [here](https://github.com/elgerpus/DeCP-Live/) or to download the the ready-to-go virtual machine with both DeCP and DeCP-Live pre-installed, available [here](https://drive.google.com/open?id=1Lqx7kxWMlpRCY1b9slrH0mt_pVT9-p4f). 
* [DeCP-Live](http://github.com/elgerpus/DeCP-Live/) provides a web-interface where a search batch can be created and the results browsed an visualized.\
		  ![DeCP-Live submitting a batch query](img/decplive_submitbatch2.png) 
		  ![DeCP-Live browsing all batch results](img/decplive_batchresultlist2.png)
 		  ![DeCP-Live results for a single batch](img/decplive_resultbatch2.png) 
		  ![DeCP-Live results for a single query image](img/decplive_resultqueryimage2.png)


## VM info: 

The virtual machine is installed into Oracle's [VirtualBox](https://www.virtualbox.org/).
 * Google drive [link to VM](https://drive.google.com/open?id=1Lqx7kxWMlpRCY1b9slrH0mt_pVT9-p4f) (it is a ~7GB .zip file).
 * Login info for VM is; username: decp and password: decplive
 * The VM is configured to nat ports to the host and thus you can access the DeCP-Live web-interface by opening your favorite browser and navigate to http://localhost:9080 once the VM is up and running. 
 * To use the search engine you will however need to log in and start it manually (see ~/README file in VM).


## Syntax of input and output text-files

The DeCP engine uses a custom syntax for its input and output text files (the batch-query, batch-result and image results).
All files have in common a header line that represents colon separated parameters. All other lines of the files are paths to files, either images or other result files.  

* Query ("query".batch) 
  * The fields of the header line are "b : k : m", where b is the search expansion factor; k is the size of the k-nearest neighborhood; and m is the number of result images to keep for each query image.
  * The other n-lines are the paths to the query images for this batch.\
   ![Query batch configuration](img/query_batch.png)
  
* Batch result (batch.res)
  * For each batch a specific folder (directory) is created named after the "query".batch. In this folder a "batch.res" file is created that holds info on the batch search and links result files for each image in the batch. 
  * The fields of the header line in "batch.res" is "b : k : m : t", where b is the search expansion factor; k is the size of the k-nearest neighborhood used; m is the number of result images to keep for each query image; and t is the total time the search of this batch took in seconds. 
  * The other n-lines are paths to the result-file for each query image in the batch.\
  ![Query batch results](img/batch_res.png)

* Image result ("imagename".res)
  * For each query image in a search batch a result file is created in the search batch folder. The header of this file holds "p:f" where p is the path to the query image and f is the number of SIFT features extracted from it.
  * The other m-lines are a ranked list of results. Each line is also colon separated lines:, the first value is the path to the result images and the second is the number of features that matched matched.\
  ![Query image result](img/queryimage_res.png)


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

* [Towards Engineering a Web-Scale Multimedia Service: A Case Study Using Spark,](https://hal.inria.fr/hal-01416089/document) published in the proceedings of the 8th ACM conference on Multimedia Systems (MMSys), June, 2017. 
* [Prototyping a Web-Scale Multimedia Retrieval Service Using Spark,](https://dl.acm.org/citation.cfm?id=3209662) published in ACM Transactions on Multimedia Computing, Communications, and Applications (TOMM), Volume 14, Issue 3s, Article No. 65, August, 2018.

## License

This project is licensed under the [MIT licence](LICENSE.md)

## Acknowledgements

This work was supported in part by the Inria@SiliconValley program, DHS Award HSHQDC-16-3-00083, NSF CISE Expeditions Award CCF-1139158, DOE Award SN10040 DE-SC0012463, and DARPA XData Award FA8750-12-2-0331, and gifts from Amazon Web Services, Google, IBM, SAP, The Thomas and Stacey Siebel Foundation, Apple Inc., Arimo, Blue Goji, Bosch, Cisco, Cray, Cloudera, Ericsson, Facebook, Fujitsu, HP, Huawei, Intel, Microsoft, Mitre, Pivotal, Samsung, Schlum\-berger, Splunk, State Farm and VMware.
