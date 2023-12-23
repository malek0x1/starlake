package ai.starlake.job.sink.jdbc

import ai.starlake.config.Settings
import ai.starlake.extract.JdbcDbUtils
import ai.starlake.utils._
import com.google.cloud.bigquery.JobInfo.WriteDisposition
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.execution.datasources.jdbc.JdbcOptionsInWrite
import org.apache.spark.sql.jdbc.JdbcDialect

import java.sql.Connection
import scala.util.{Failure, Success, Try}

class sparkJdbcLoader(
  cliConfig: JdbcConnectionLoadConfig
)(implicit val settings: Settings)
    extends SparkJob {

  override def name: String = s"cnxload-JDBC-${cliConfig.outputDomainAndTableName}"

  def tableExists(conn: java.sql.Connection, url: String, domainAndTableName: String): Boolean = {
    JdbcDbUtils.tableExists(conn, url, domainAndTableName)
  }

  val conf = session.sparkContext.hadoopConfiguration
  logger.info(s"JDBC Config $cliConfig")

  val jdbcOptions = JdbcDbUtils.jdbcOptions(cliConfig.options, cliConfig.format)

  def runJDBC(): Try[SparkJobResult] = {
    val inputPath = cliConfig.sourceFile
    logger.info(s"Input path $inputPath")

    val writeMode =
      if (cliConfig.writeDisposition == WriteDisposition.WRITE_TRUNCATE) SaveMode.Overwrite
      else SaveMode.Append
    Try {
      val sourceDF =
        inputPath match {
          case Left(path) => session.read.format(settings.appConfig.defaultWriteFormat).load(path)
          case Right(df)  => df
        }
      val outputDomain = cliConfig.outputDomainAndTableName.split("\\.")(0)
      JdbcDbUtils.withJDBCConnection(jdbcOptions) { conn =>
        val url = jdbcOptions("url")
        val exists = tableExists(conn, url, cliConfig.outputDomainAndTableName)
        if (!exists && settings.appConfig.createSchemaIfNotExists) {
          logger.info(s"Schema $outputDomain not found, trying to create it")
          JdbcDbUtils.createSchema(outputDomain, conn)
        } else {
          logger.info(s"Schema $outputDomain found")
        }
        val schema = sourceDF.schema
        if (SparkUtils.isFlat(schema) && exists) {
          val existingSchema =
            SparkUtils.getSchemaOption(conn, jdbcOptions, cliConfig.outputDomainAndTableName)
          val addedSchema = SparkUtils.added(schema, existingSchema.getOrElse(schema))
          val deletedSchema = SparkUtils.dropped(schema, existingSchema.getOrElse(schema))
          val alterTableDropColumns =
            SparkUtils.alterTableDropColumnsString(
              deletedSchema,
              cliConfig.outputDomainAndTableName
            )
          if (alterTableDropColumns.nonEmpty) {
            logger.info(
              s"later table ${cliConfig.outputDomainAndTableName} with ${alterTableDropColumns.size} columns to drop"
            )
            logger.debug(s"alter table ${alterTableDropColumns.mkString("\n")}")
          }
          val alterTableAddColumns =
            SparkUtils.alterTableAddColumnsString(addedSchema, cliConfig.outputDomainAndTableName)

          if (alterTableAddColumns.nonEmpty) {
            logger.info(
              s"later table ${cliConfig.outputDomainAndTableName} with ${alterTableAddColumns.size} columns to add"
            )
            logger.debug(s"alter table ${alterTableAddColumns.mkString("\n")}")
          }
          alterTableDropColumns.foreach(JdbcDbUtils.executeAlterTable(_, conn))
          alterTableAddColumns.foreach(JdbcDbUtils.executeAlterTable(_, conn))
        } else {
          val optionsWrite =
            new JdbcOptionsInWrite(url, cliConfig.outputDomainAndTableName, jdbcOptions)

          logger.info(
            s"Table ${cliConfig.outputDomainAndTableName} not found, creating it with schema $schema"
          )
          SparkUtils.createTable(
            conn,
            cliConfig.outputDomainAndTableName,
            schema,
            caseSensitive = false,
            optionsWrite
          )
        }
      }

      // table exists at this point
      val dfw = sourceDF.write
        .format(cliConfig.format)
        .option("dbtable", cliConfig.outputDomainAndTableName)

      val finalDfw =
        if (cliConfig.format == "jdbc")
          dfw
            .option("truncate", cliConfig.writeDisposition == WriteDisposition.WRITE_TRUNCATE)
        else
          dfw

      finalDfw
        .mode(writeMode.toString)
        .options(cliConfig.options)
        .save()

      logger.info(
        s"JDBC save done to table ${cliConfig.outputDomainAndTableName} at ${cliConfig.options}"
      )
      SparkJobResult(None)
    }
  }

  private def truncateTable(
    conn: Connection,
    jdbcOptions: Map[String, String]
  ): Unit = {
    // Some database do not support truncate during save
    // Truncate should be done manually in pre-sql
    // https://stackoverflow.com/questions/59451275/how-to-generate-a-spark-sql-truncate-query-without-only
    if (jdbcOptions.get("supportTruncateOnInsert").contains("false")) {
      val jdbcDialect = jdbcOptions.get("url") match {
        case Some(url) =>
          SparkUtils.dialect(url)
        case None =>
          logger.warn("No url found in jdbc options. Using TRUNCATE TABLE")
          new JdbcDialect {
            override def canHandle(url: String): Boolean = true
          }
      }

      val truncateSql = jdbcDialect.getTruncateQuery(cliConfig.outputDomainAndTableName)

      // do not fail on exception. Truncate may fail is table does not exist
      JdbcDbUtils.execute(truncateSql, conn) match {
        case Failure(e) =>
          logger.warn(
            s"Truncate failed on table ${cliConfig.outputDomainAndTableName} with error $e"
          )
        case Success(_) =>
          logger.info(s"Truncate done on table ${cliConfig.outputDomainAndTableName}")
      }
    }
  }

  /** Just to force any job to implement its entry point using within the "run" method
    *
    * @return
    *   : Spark Session used for the job
    */
  override def run(): Try[JobResult] = {
    val res = runJDBC()
    Utils.logFailure(res, logger)
  }
}
