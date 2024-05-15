/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package ai.starlake.schema.handlers

import ai.starlake.config.Settings
import ai.starlake.schema.model._
import ai.starlake.utils.YamlSerde
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.types.{ArrayType, StructField, StructType}

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import scala.util.Try

object InferSchemaHandler {
  val datePattern = "[0-9]{4}-[0-9]{2}-[0-9]{2}".r.pattern

  def parseIsoInstant(str: String): Boolean = {
    try {
      ZonedDateTime.parse(str, DateTimeFormatter.ISO_DATE_TIME)
      true
    } catch {
      case e: java.time.format.DateTimeParseException => false
    }
  }

  /** * Traverses the schema and returns a list of attributes.
    *
    * @param schema
    *   Schema so that we find all Attributes
    * @return
    *   List of Attributes
    */
  def createAttributes(
    lines: List[Array[String]],
    schema: StructType,
    format: Format
  )(implicit settings: Settings): List[Attribute] = {
    val schemaWithIndex: Seq[(StructField, Int)] = schema.zipWithIndex

    schemaWithIndex
      .map { case (row, index) =>
        val required = if (!row.nullable) Some(true) else None
        row.dataType match {
          // if the datatype is a struct {...} containing one or more other field
          case st: StructType =>
            Attribute(
              row.name,
              row.dataType.typeName,
              Some(false),
              required,
              attributes = createAttributes(lines, st, format)
            )

          case dt: ArrayType =>
            dt.elementType match {
              case st: StructType =>
                // if the array contains elements of type struct.
                // {people: [{name:Person1, age:22},{name:Person2, age:25}]}
                Attribute(
                  row.name,
                  st.typeName,
                  Some(true),
                  required,
                  attributes = createAttributes(lines, st, format)
                )
              case _ =>
                // if it is a regular array. {ages: [21, 25]}
                Attribute(
                  row.name,
                  PrimitiveType.from(dt.elementType).value,
                  Some(true),
                  required
                )
            }

          // if the datatype is a simple Attribute
          case _ =>
            val cellType =
              if (row.dataType.typeName == "string") {
                val timestampCandidates = lines.map(row => row(index)).flatMap(Option(_))
                if (timestampCandidates.isEmpty)
                  "string"
                else if (timestampCandidates.forall(v => parseIsoInstant(v)))
                  "iso_date_time"
                else if (timestampCandidates.forall(v => datePattern.matcher(v).matches()))
                  "date"
                else
                  "string"
              } else if (
                row.dataType.typeName == "timestamp" && Set(
                  Format.DSV,
                  Format.POSITION,
                  Format.JSON_FLAT
                ).contains(format)
              ) {
                // We handle here the case when it is a date and not a timestamp
                val timestamps = lines.map(row => row(index)).flatMap(Option(_))
                if (timestamps.forall(v => datePattern.matcher(v).matches()))
                  "date"
                else
                  "timestamp"
              } else
                PrimitiveType.from(row.dataType).value
            Attribute(row.name, cellType, Some(false), required)
        }
      }
  }.toList

  /** * builds the Metadata case class. check case class metadata for attribute definition
    *
    * @param format
    *   : DSV by default
    * @param array
    *   : Is a json stored as a single object array ? false by default
    * @param withHeader
    *   : does the dataset has a header ? true bu default
    * @param separator
    *   : the column separator, ';' by default
    * @return
    */

  def createMetaData(
    format: Format,
    array: Option[Boolean] = None,
    withHeader: Option[Boolean] = None,
    separator: Option[String] = None,
    options: Option[Map[String, String]] = None
  ): Metadata =
    Metadata(
      format = Some(format),
      encoding = None,
      multiline = None,
      array = if (array.contains(true)) array else None,
      withHeader = withHeader,
      separator = separator,
      options = options
    )

  /** * builds the Schema case class
    *
    * @param name
    *   : Schema name, must be unique in the domain. Will become the hive table name
    * @param pattern
    *   : filename pattern to which this schema must be applied
    * @param attributes
    *   : datasets columns
    * @param metadata
    *   : Dataset metadata
    * @return
    */

  def createSchema(
    name: String,
    pattern: Pattern,
    comment: Option[String],
    attributes: List[Attribute],
    metadata: Option[Metadata]
  ): Schema =
    Schema(
      name = name,
      pattern = pattern,
      attributes = attributes,
      metadata = metadata,
      comment = comment
    )

  /** * Builds the Domain case class
    *
    * @param name
    *   : Domain name
    * @param directory
    *   : Folder on the local filesystem where incomping files are stored. This folder will be
    *   scanned regurlaly to move the dataset to the cluster
    * @param metadata
    *   : Default Schema meta data.
    * @param schemas
    *   : List of schema for each dataset in this domain
    * @return
    */

  def createDomain(
    name: String,
    metadata: Option[Metadata] = None,
    schemas: List[Schema] = Nil
  ): Domain =
    Domain(name = name, metadata = metadata, tables = schemas)

  /** * Generates the YAML file using the domain object and a savepath
    *
    * @param domain
    *   Domain case class
    * @param savePath
    *   path to save files.
    */
  def generateYaml(domain: Domain, saveDir: String, clean: Boolean)(implicit
    settings: Settings
  ): Try[Path] = Try {
    implicit val storageHandler: StorageHandler = settings.storageHandler()

    /** load: metadata: directory: "{{incoming_path}}"
      */
    val domainFolder = new Path(saveDir, domain.name)
    storageHandler.mkdirs(domainFolder)
    val configPath = new Path(domainFolder, "_config.sl.yml")
    if (!storageHandler.exists(configPath)) {
      YamlSerde.serializeToPath(configPath, domain.copy(tables = Nil))
    }
    val table = domain.tables.head
    val tablePath = new Path(domainFolder, s"${table.name}.sl.yml")
    if (storageHandler.exists(tablePath) && !clean) {
      throw new Exception(
        s"Could not write table ${domain.tables.head.name} already defined in file $tablePath"
      )
    }
    YamlSerde.serializeToPath(tablePath, table)
    tablePath
  }
}
