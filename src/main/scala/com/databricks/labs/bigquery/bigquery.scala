package com.databricks.labs

import org.apache.spark.sql.{DataFrame, SQLContext}

import scala.language.implicitConversions

package object bigquery {

  /**
    * Enhanced version of [[SQLContext]] with BigQuery support.
    */
  implicit def makebigQueryContext(sql: SQLContext): BigQuerySQLContext =
    new BigQuerySQLContext(sql)

  /**
    * Enhanced version of [[DataFrame]] with BigQuery support.
    */
  implicit def makebigQueryDataFrame(df: DataFrame): BigQueryDataFrame =
    new BigQueryDataFrame(df)
}
