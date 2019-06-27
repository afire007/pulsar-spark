/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.pulsar

import java.lang.{Integer => JInt}
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Map => JMap}

import scala.reflect.ClassTag
import scala.collection.JavaConverters._
import com.google.common.collect.Sets
import io.streamnative.tests.pulsar.service.{PulsarService, PulsarServiceFactory, PulsarServiceSpec}
import org.apache.commons.lang.exception.ExceptionUtils
import org.apache.pulsar.client.admin.{PulsarAdmin, PulsarAdminException}
import org.apache.pulsar.client.api.{MessageId, Producer, PulsarClient, Schema}
import org.apache.pulsar.common.naming.TopicName
import org.apache.pulsar.common.protocol.schema.PostSchemaPayload
import org.apache.pulsar.common.schema.{SchemaInfo, SchemaType}
import org.scalatest.BeforeAndAfterAll
import org.apache.spark.SparkFunSuite
import org.apache.spark.util.Utils

/**
 * A trait to clean cached Pulsar producers in `afterAll`
 */
trait PulsarTest extends BeforeAndAfterAll {
  self: SparkFunSuite =>

  var pulsarService : PulsarService = _
  var serviceUrl: String = _
  var adminUrl: String = _

  override def beforeAll(): Unit = {
    val spec: PulsarServiceSpec = PulsarServiceSpec.builder()
      .clusterName("standalone")
      .enableContainerLogging(false)
      .build()

    pulsarService = PulsarServiceFactory.createPulsarService(spec)
    pulsarService.start()

    val uris = pulsarService
      .getServiceUris.asScala
      .filter(_ != null).partition(_.getScheme == "pulsar")

    serviceUrl = uris._1(0).toString
    adminUrl = uris._2(0).toString

    Utils.tryWithResource(PulsarAdmin.builder().serviceHttpUrl(adminUrl).build()) { admin =>
      admin.namespaces().createNamespace("public/default", Sets.newHashSet("standalone"))
    }

    logInfo(s"Successfully started pulsar service at cluster ${spec.clusterName}")

    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    CachedPulsarClient.clear()
    if (pulsarService != null) {
      pulsarService.stop()
    }
  }

  def getAllTopicsSize(): Seq[(String, MessageId)] = {
    Utils.tryWithResource(PulsarAdmin.builder().serviceHttpUrl(adminUrl).build()) { admin =>
      val tps = admin.namespaces().getTopics("public/default").asScala
      tps.map { tp =>
        (tp, PulsarSourceUtils.seekableLatestMid(admin.topics().getLastMessageId(tp)))
      }
    }
  }

  /** Java-friendly function for sending messages to the Pulsar */
  def sendMessages(topic: String, messageToFreq: JMap[String, JInt]): Unit = {
    sendMessages(topic, Map(messageToFreq.asScala.mapValues(_.intValue()).toSeq: _*))
  }

  /** Send the messages to the Pulsar */
  def sendMessages(topic: String, messageToFreq: Map[String, Int]): Unit = {
    val messages = messageToFreq.flatMap { case (s, freq) => Seq.fill(freq)(s) }.toArray
    sendMessages(topic, messages)
  }

  /** Send the array of messages to the Pulsar */
  def sendMessages(topic: String, messages: Array[String]): Seq[(String, MessageId)] = {
    sendMessages(topic, messages, None)
  }

  /** Send the array of messages to the Pulsar using specified partition */
  def sendMessages(
      topic: String,
      messages: Array[String],
      partition: Option[Int]): Seq[(String, MessageId)] = {

    val client = PulsarClient.builder()
      .serviceUrl(serviceUrl)
      .build()

    val producer = client.newProducer().topic(topic).create()

    val offsets = try {
      messages.map { m =>
        val mid = producer.send(m.getBytes(StandardCharsets.UTF_8))
        logInfo(s"\t Sent $m of mid: $mid")
        (m, mid)
      }
    } finally {
      producer.flush()
      producer.close()
      client.close()
    }
    offsets
  }

  def sendTypedMessages[T: ClassTag](
      topic: String,
      tpe: SchemaType,
      messages: Seq[T],
      partition: Option[Int]): Seq[MessageId] = {

    val client = PulsarClient.builder()
      .serviceUrl(serviceUrl)
      .build()

    val producer: Producer[T] = tpe match {
      case SchemaType.BOOLEAN   => client.newProducer(Schema.BOOL).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.BYTES     => client.newProducer(Schema.BYTES).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.DATE      => client.newProducer(Schema.DATE).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.STRING    => client.newProducer(Schema.STRING).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.TIMESTAMP => client.newProducer(Schema.TIMESTAMP).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.INT8      => client.newProducer(Schema.INT8).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.DOUBLE    => client.newProducer(Schema.DOUBLE).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.FLOAT     => client.newProducer(Schema.FLOAT).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.INT32     => client.newProducer(Schema.INT32).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.INT64     => client.newProducer(Schema.INT64).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.INT16     => client.newProducer(Schema.INT16).topic(topic).create().asInstanceOf[Producer[T]]
      case SchemaType.AVRO      =>
        val cls = implicitly[ClassTag[T]].runtimeClass
        client.newProducer(Schema.AVRO(cls)).topic(topic).create().asInstanceOf[Producer[T]]
      case _ => throw new NotImplementedError(s"not supported type $tpe")
    }

    val offsets = try {
      messages.map { m =>
        val mid = producer.send(m)
        logInfo(s"\t Sent $m of mid: $mid")
        mid
      }
    } finally {
      producer.flush()
      producer.close()
      client.close()
    }
    offsets
  }

  def getEarliestOffsets(topics: Set[String]): Map[String, MessageId] = {
    val client = PulsarClient.builder()
      .serviceUrl(serviceUrl)
      .build()
    val t2id = topics.map { tp =>
      val consumer = client.newReader().startMessageId(MessageId.earliest).create()
      val mid = consumer.readNext().getMessageId
      consumer.close()
      (tp, mid)
    }.toMap
    client.close()
    t2id
  }

  def getLatestOffsets(topics: Set[String]): Map[String, MessageId] = {
    Utils.tryWithResource(PulsarAdmin.builder().serviceHttpUrl(adminUrl).build()) { admin =>
      topics.map { tp =>
        (tp, PulsarSourceUtils.seekableLatestMid(admin.topics().getLastMessageId(tp)))
      }.toMap
    }
  }

  def createPulsarSchema(topic: String, schemaInfo: SchemaInfo): Unit = {
    assert(schemaInfo != null, "schemaInfo shouldn't be null")
    val pl = new PostSchemaPayload()
    pl.setType(schemaInfo.getType.name())
    pl.setSchema(new String(schemaInfo.getSchema, UTF_8))
    pl.setProperties(schemaInfo.getProperties)
    Utils.tryWithResource(PulsarAdmin.builder().serviceHttpUrl(adminUrl).build()) { admin =>
      try {
        admin.schemas().createSchema(TopicName.get(topic).toString, pl)
      } catch {
        case e: PulsarAdminException if e.getStatusCode == 404 =>
          logError(s"Create schema for ${TopicName.get(topic).toString} got 404")
        case e: Throwable => throw new RuntimeException(
          s"Failed to create schema for ${TopicName.get(topic).toString}: " +
            ExceptionUtils.getRootCause(e).getLocalizedMessage, e)
      }
    }
  }
}
