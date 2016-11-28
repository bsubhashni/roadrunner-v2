RoadRunner: a simple Couchbase Server workload generator for Java
==================================================================

RoadRunner is a workload generator written in Java for Couchbase
Server. It is intended to be used as a standalone jar. It provides
support for customizing lots of aspects, like number of threads per
CouchbaseClient, number of CouchbaseClients, amount of docs to store
and so on.

```
Running the `jar` with `-h` shows the supported options:
usage: roadrunner
-b,--bucket <arg>          Name of the bucket (default: "default")
-B,--batch-size <arg>      Batch size (default "100")
-c,--num-clients <arg>     Number of CouchbaseClient objects (default:"1")
-C,--class <arg>           Class name from the sample classes (default "Simple")
-d,--num-docs <arg>        Number of documents to work with (default:"1000")
-g,--read-ratio <arg>      Read Ratio  (default: "50")
-h,--help                  Print this help message
-n,--nodes <arg>           List of nodes to connect, separated with "," (default: "127.0.0.1")
-P,--phase <arg>           load/run phase (default: "run")
-p,--password <arg>        Password of the bucket (default: "")
-R,--ramp <arg>            Ramp-Up time in seconds - ignored ops(default: "0")
-s,--sampling <arg>        % Sample Rate (default "100%")
-t,--num-threads <arg>     Number of worker threads per CouchbaseClient object (default: "1")
-w,--write-ratio <arg>     Write Ratio (default: "50")
-z,--min-thinktime <arg>   Minimum think time (default "1")
-Z,--max-thinktime <arg>   Maximum think time (default "1000")
```
To use:

Load phase
```
java -jar roadrunner.jar  -n <server host address> -b default -d 1000000 -P load
```
Run phase
```
java -jar roadrunner.jar  -n <server host address> -b default -d 1000000 -B 10 -g 50 -w 50 -t 10 -P run
```
