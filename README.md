MAINTENANCE MODE
================

THIS PROJECT IS IN MAINTENANCE MODE DUE TO THE FACT THAT IT'S NOT WIDELY USED WITHIN SPOTIFY. WE'LL PROVIDE BEST EFFORT SUPPORT FOR ISSUES AND PULL REQUESTS BUT DO EXPECT DELAY IN RESPONSES.

spark-bigquery
==============

[![Build Status](https://travis-ci.org/spotify/spark-bigquery.svg?branch=master)](https://travis-ci.org/spotify/spark-bigquery)
[![GitHub license](https://img.shields.io/github/license/spotify/spark-bigquery.svg)](./LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.spotify/spark-bigquery_2.11.svg)](https://maven-badges.herokuapp.com/maven-central/com.spotify/spark-bigquery_2.11)

Google BigQuery support for Spark, SQL, and DataFrames.

| spark-bigquery version | Spark version | Comment |
| :--------------------: | ------------- | ------- |
| 0.2.x | 2.x.y | Active development |
| 0.1.x | 1.x.y | Development halted |

Building:

```sbt clean assembly```

Assembly doesn't like the latest version of Java (currently 11) so set JAVA_HOME to point to Java 8.

To use it in a local SBT console:

```scala
import com.spotify.spark.bigquery._

// Set up GCP credentials
sqlContext.setGcpJsonKeyFile("<JSON_KEY_FILE>")

// Set up BigQuery project and bucket
sqlContext.setBigQueryProjectId("<BILLING_PROJECT>")
sqlContext.setBigQueryGcsBucket("<GCS_BUCKET>")

// Set up BigQuery dataset location, default is US
sqlContext.setBigQueryDatasetLocation("<DATASET_LOCATION>")
```

Usage:

```scala
// Load everything from a table
val table = sqlContext.bigQueryTable("bigquery-public-data:samples.shakespeare")

// Load results from a SQL query
// Only legacy SQL dialect is supported for now
val df = sqlContext.bigQuerySelect(
  "SELECT word, word_count FROM [bigquery-public-data:samples.shakespeare]")

  // Save data to a table
df.saveAsBigQueryTable("my-project:my_dataset.my_table")
```

# License

Copyright 2016 Spotify AB.

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
