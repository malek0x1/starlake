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

package com.ebiznext.comet.config

import java.util.Locale

import com.ebiznext.comet.schema.handlers.StorageHandler
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.fasterxml.jackson.databind.{
  DeserializationContext,
  JsonDeserializer,
  JsonSerializer,
  SerializerProvider
}
import org.apache.hadoop.fs.Path

/**
  * Utilities methods to reference datasets paths
  * Datasets paths are constructed as follows :
  *   - root path defined by the COMET_DATASETS env var or datasets applciation property
  *   - followed by the area name
  *   - followed by the the domain name
  */
object DatasetArea {

  def path(domain: String, area: String)(implicit settings: Settings) =
    new Path(s"${settings.comet.datasets}/$area/$domain")

  def path(domainPath: Path, schema: String) = new Path(domainPath, schema)

  /**
    * datasets waiting to be ingested are stored here
    *
    * @param domain : Domain Name
    * @return Absolute path to the pending folder of domain
    */
  def pending(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.comet.area.pending)

  /**
    * datasets with a file name that could not match any schema file name pattern in the specified domain
    * are marked unresolved by being stored in this folder.
    *
    * @param domain : Domain name
    * @return Absolute path to the pending unresolved folder of domain
    */
  def unresolved(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.comet.area.unresolved)

  /**
    * Once ingested datasets are archived in this folder.
    *
    * @param domain : Domain name
    * @return Absolute path to the archive folder of domain
    */
  def archive(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.comet.area.archive)

  /**
    * Datasets of the specified domain currently being ingested are located in this folder
    *
    * @param domain : Domain name
    * @return Absolute path to the ingesting folder of domain
    */
  def ingesting(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.comet.area.ingesting)

  /**
    * Valid records for datasets the specified domain are stored in this folder.
    *
    * @param domain : Domain name
    * @return Absolute path to the ingesting folder of domain
    */
  def accepted(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.comet.area.accepted)

  /**
    * Invalid records and the reason why they have been rejected for the datasets of the specified domain are stored in this folder.
    *
    * @param domain : Domain name
    * @return Absolute path to the rejected folder of domain
    */
  def rejected(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.comet.area.rejected)

  /**
    * Default target folder for autojobs applied to datasets in this domain
    *
    * @param domain : Domain name
    * @return Absolute path to the business folder of domain
    */
  def business(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.comet.area.business)

  def metadata(implicit settings: Settings): Path =
    new Path(s"${settings.comet.metadata}")

  def types(implicit settings: Settings): Path =
    new Path(metadata, "types")

  def mapping(implicit settings: Settings): Path =
    new Path(metadata, "mapping")

  def domains(implicit settings: Settings): Path =
    new Path(metadata, "domains")

  def jobs(implicit settings: Settings): Path =
    new Path(metadata, "jobs")

  /**
    *
    * @param storage
    */
  def init(
    storage: StorageHandler
  )(implicit settings: Settings): Unit = {
    List(metadata, types, domains).foreach(storage.mkdirs)
  }

  def initDomains(storage: StorageHandler, domains: Iterable[String])(
    implicit settings: Settings
  ): Unit = {
    init(storage)
    domains.foreach { domain =>
      List(pending _, unresolved _, archive _, accepted _, rejected _, business _)
        .map(_(domain))
        .foreach(storage.mkdirs)
    }
  }
}

/**
  * After going through the data pipeline
  * a dataset may be referenced through a Hive table in a Hive Database.
  * For each input domain, 3 Hive databases may be created :
  *     - The rejected database : contains tables referencing rejected records for each schema in the domain
  *     - The accepted database : contains tables referencing
  *     - The business database : contains tables where autjob tables are created by default
  *     - The ciustom database : contains tables where autojob tables are created when a specific area is defined
  */
object StorageArea {

  def fromString(value: String)(implicit settings: Settings): StorageArea = {

    val rejected = settings.comet.area.rejected.toLowerCase(Locale.ROOT)
    val accepted = settings.comet.area.accepted.toLowerCase(Locale.ROOT)
    val business = settings.comet.area.business.toLowerCase(Locale.ROOT)

    val lcValue = value.toLowerCase(Locale.ROOT)

    lcValue match {
      case _ if lcValue == settings.comet.area.rejectedFinal => StorageArea.rejected
      case _ if lcValue == settings.comet.area.acceptedFinal => StorageArea.accepted
      case _ if lcValue == settings.comet.area.businessFinal => StorageArea.business
      case custom                                            => StorageArea.Custom(custom)
    }
  }

  case object rejected extends StorageArea {
    def value: String = "rejected"
  }
  case object accepted extends StorageArea {
    def value: String = "accepted"
  }
  case object business extends StorageArea {
    def value: String = "business"
  }

  final case class Custom(value: String) extends StorageArea

  def area(domain: String, area: StorageArea): String = s"${domain}_${area.value}"

}

final class StorageAreaSerializer extends JsonSerializer[StorageArea] {
  override def serialize(
    value: StorageArea,
    gen: JsonGenerator,
    serializers: SerializerProvider
  ): Unit = {
    val settings = serializers.getAttribute(classOf[Settings]).asInstanceOf[Settings]
    require(settings != null, "the SerializationContext lacks a Settings instance")

    val strValue = value match {
      case StorageArea.accepted            => settings.comet.area.accepted
      case StorageArea.rejected            => settings.comet.area.rejected
      case StorageArea.business            => settings.comet.area.business
      case StorageArea.Custom(customValue) => customValue
    }

    gen.writeString(strValue)
  }
}
final class StorageAreaDeserializer extends JsonDeserializer[StorageArea] {
  override def deserialize(jp: JsonParser, ctx: DeserializationContext): StorageArea = {
    val settings = ctx.getAttribute(classOf[Settings]).asInstanceOf[Settings]
    require(settings != null, "the DeserializationContext lacks a Settings instance")

    val value = jp.readValueAs[String](classOf[String])
    StorageArea.fromString(value)(settings)
  }
}

@JsonSerialize(using = classOf[StorageAreaSerializer])
@JsonDeserialize(using = classOf[StorageAreaDeserializer])
sealed abstract class StorageArea {
  def value: String
  override def toString: String = value
}
