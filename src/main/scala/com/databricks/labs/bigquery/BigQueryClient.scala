/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.databricks.labs.bigquery

import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.bigquery.model._
import com.google.api.services.bigquery.{Bigquery, BigqueryScopes}
import com.google.cloud.hadoop.io.bigquery._
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.Progressable
import org.joda.time.Instant
import org.joda.time.format.DateTimeFormat
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.util.Random
import scala.util.control.NonFatal

private[bigquery] object BigQueryClient {
  // the following is specific to this connector...
  val STAGING_DATASET_PREFIX = "bq.staging_dataset.prefix"
  val STAGING_DATASET_PREFIX_DEFAULT = "spark_bigquery_staging_"
  val STAGING_DATASET_LOCATION = "bq.staging_dataset.location"

  val STAGING_DATASET_LOCATION_DEFAULT = "US"
  val STAGING_DATASET_TABLE_EXPIRATION_MS = 86400000L
  val STAGING_DATASET_DESCRIPTION = "Spark BigQuery staging dataset"

  private var instance: BigQueryClient = null

  def getInstance(conf: Configuration): BigQueryClient = {
    if (instance == null) {
      instance = new BigQueryClient(conf)
    }
    instance
  }
}

private[bigquery] class BigQueryClient(conf: Configuration) {

  import BigQueryClient._

  private val logger: Logger = LoggerFactory.getLogger(classOf[BigQueryClient])

  private val SCOPES = List(BigqueryScopes.BIGQUERY).asJava

  private val googleCredential: GoogleCredential = {
    // TODO get this working for pk12 credentials

    val jsonKeyFileLocation: String = conf.get("fs.gs.auth.service.account.json.keyfile", "")
    //"fs.gs.auth.service.account.json.keyfile"
    if (jsonKeyFileLocation.nonEmpty) {
      var optionFileInputStream: Option[FileInputStream] = None

      try {
        optionFileInputStream = Some(new FileInputStream(jsonKeyFileLocation))

        GoogleCredential.fromStream(optionFileInputStream.get).createScoped(SCOPES)
      } finally {
        if (optionFileInputStream.isDefined) {
          optionFileInputStream.get.close()
        }
      }
    } else {
      GoogleCredential.getApplicationDefault.createScoped(SCOPES)
    }
  }

  private val bigquery: Bigquery =
    new Bigquery.Builder(new NetHttpTransport, new JacksonFactory, googleCredential)
      .setApplicationName("spark-bigquery")
      .build()

  private def projectId: String = conf.get(BigQueryConfiguration.PROJECT_ID_KEY)

  private case class CacheKey(sqlQuery: String, useStandardSql: Boolean)

  private val queryCache: LoadingCache[CacheKey, TableReference] =
    CacheBuilder.newBuilder()
      .expireAfterWrite(STAGING_DATASET_TABLE_EXPIRATION_MS, TimeUnit.MILLISECONDS)
      .build[CacheKey, TableReference](new CacheLoader[CacheKey, TableReference] {
      override def load(key: CacheKey): TableReference = {
        val sqlQuery = key.sqlQuery
        logger.info(s"Executing query $sqlQuery")

        val location = conf.get(STAGING_DATASET_LOCATION, STAGING_DATASET_LOCATION_DEFAULT)
        val destinationTable = temporaryTable(location)
        val tableName = BigQueryStrings.toString(destinationTable)
        logger.info(s"Destination table: $destinationTable")

        val job = createQueryJob(sqlQuery, destinationTable,
          dryRun = false, useLegacySql = !key.useStandardSql)
        waitForJob(job)
        destinationTable
      }
    })

  private def inConsole = Thread.currentThread().getStackTrace.exists(
    _.getClassName.startsWith("scala.tools.nsc.interpreter."))

  private val PRIORITY = if (inConsole) "INTERACTIVE" else "BATCH"
  private val TABLE_ID_PREFIX = "spark_bigquery"
  private val JOB_ID_PREFIX = "spark_bigquery"
  private val TIME_FORMATTER = DateTimeFormat.forPattern("yyyyMMddHHmmss")

  /**
    * Perform a BigQuery SELECT query and save results to a temporary table.
    */
  def query(sqlQuery: String, useStandardSql: Boolean = false): TableReference =
    queryCache.get(CacheKey(sqlQuery, useStandardSql))

  /**
    * Load an Avro data set on GCS to a BigQuery table.
    */
  def load(gcsPath: String, destinationTable: TableReference,
           writeDisposition: WriteDisposition.Value = null,
           createDisposition: CreateDisposition.Value = null): Unit = {
    val tableName = BigQueryStrings.toString(destinationTable)
    logger.info(s"Loading $gcsPath into $tableName")
    var loadConfig = new JobConfigurationLoad()
      .setDestinationTable(destinationTable)
      .setSourceFormat("AVRO")
      .setSourceUris(List(gcsPath + "/*.avro").asJava)
    if (writeDisposition != null) {
      loadConfig = loadConfig.setWriteDisposition(writeDisposition.toString)
    }
    if (createDisposition != null) {
      loadConfig = loadConfig.setCreateDisposition(createDisposition.toString)
    }

    val jobConfig = new JobConfiguration().setLoad(loadConfig)
    val jobReference = createJobReference(projectId, JOB_ID_PREFIX)
    val job = new Job().setConfiguration(jobConfig).setJobReference(jobReference)
    bigquery.jobs().insert(projectId, job).execute()
    waitForJob(job)
  }

  private def waitForJob(job: Job): Unit = {
    BigQueryUtils.waitForJobCompletion(bigquery, projectId, job.getJobReference, new Progressable {
      override def progress(): Unit = {}
    })
  }

  private def stagingDataset(location: String): DatasetReference = {
    // Create staging dataset if it does not already exist
    val prefix = conf.get(STAGING_DATASET_PREFIX, STAGING_DATASET_PREFIX_DEFAULT)
    val datasetId = prefix + location.toLowerCase
    try {
      bigquery.datasets().get(projectId, datasetId).execute()
      logger.info(s"Staging dataset $projectId:$datasetId already exists")
    } catch {
      case e: GoogleJsonResponseException if e.getStatusCode == 404 =>
        logger.info(s"Creating staging dataset $projectId:$datasetId")
        val dsRef = new DatasetReference().setProjectId(projectId).setDatasetId(datasetId)
        val ds = new Dataset()
          .setDatasetReference(dsRef)
          .setDefaultTableExpirationMs(STAGING_DATASET_TABLE_EXPIRATION_MS)
          .setDescription(STAGING_DATASET_DESCRIPTION)
          .setLocation(location)
        bigquery
          .datasets()
          .insert(projectId, ds)
          .execute()

      case NonFatal(e) => throw e
    }
    new DatasetReference().setProjectId(projectId).setDatasetId(datasetId)
  }

  private def temporaryTable(location: String): TableReference = {
    val now = Instant.now().toString(TIME_FORMATTER)
    val tableId = TABLE_ID_PREFIX + "_" + now + "_" + Random.nextInt(Int.MaxValue)
    new TableReference()
      .setProjectId(projectId)
      .setDatasetId(stagingDataset(location).getDatasetId)
      .setTableId(tableId)
  }

  private def createQueryJob(sqlQuery: String,
                             destinationTable: TableReference,
                             dryRun: Boolean, useLegacySql: Boolean): Job = {
    var queryConfig = new JobConfigurationQuery().setUseLegacySql(useLegacySql)
      .setQuery(sqlQuery)
      .setPriority(PRIORITY)
      .setCreateDisposition("CREATE_IF_NEEDED")
      .setWriteDisposition("WRITE_EMPTY")
    if (destinationTable != null) {
      queryConfig = queryConfig
        .setDestinationTable(destinationTable)
        .setAllowLargeResults(true)
    }

    val jobConfig = new JobConfiguration().setQuery(queryConfig).setDryRun(dryRun)
    val jobReference = createJobReference(projectId, JOB_ID_PREFIX)
    val job = new Job().setConfiguration(jobConfig).setJobReference(jobReference)
    bigquery.jobs().insert(projectId, job).execute()
  }

  private def createJobReference(projectId: String, jobIdPrefix: String): JobReference = {
    val fullJobId = projectId + "-" + UUID.randomUUID().toString
    new JobReference().setProjectId(projectId).setJobId(fullJobId)
  }

}
