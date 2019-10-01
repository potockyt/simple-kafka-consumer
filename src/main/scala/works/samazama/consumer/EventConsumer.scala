package works.samazama.consumer

import akka.Done
import akka.actor.{Actor, ActorLogging, Props}
import akka.kafka._
import akka.kafka.javadsl.Committer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Keep
import EventConsumerProtocol.{Start, Stop}
import io.circe.Json
import io.circe.parser._
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}

import scala.compat.java8.FutureConverters._
import scala.concurrent.duration._

object EventConsumerProtocol {

  case object Start

  case object Stop

}

object EventConsumer {

  def props(servers: String, groupId: String, sourceTopic: String, targetTopic: String)(
    implicit mat: ActorMaterializer
  ) =
    Props(new EventConsumer(servers, groupId, sourceTopic, targetTopic))
}

class EventConsumer(bootstrapServers: String, groupId: String, sourceTopic: String, targetTopic: String)(
  implicit mat: ActorMaterializer
) extends Actor
    with ActorLogging {
  // see defaults at akka-stream-kafka_2.13-1.0.5.jar!/reference.conf
  private val consumerSettings =
    ConsumerSettings(context.system, new StringDeserializer, new ByteArrayDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")

  // see defaults at akka-stream-kafka_2.13-1.0.5.jar!/reference.conf
  private val committerSettings = CommitterSettings(context.system)
  // see defaults at akka-stream-kafka_2.13-1.0.5.jar!/reference.conf
  private val producerSettings = ProducerSettings(context.system, new StringSerializer, new ByteArraySerializer)
    .withBootstrapServers(bootstrapServers)

  // Assuming unbounded stream where we're interested in certain events in specific time window
  private val maxWindowWidth = 1000
  private val masWindowPeriod = 1.second

  private val aggregator = Consumer
    .committableSource(consumerSettings, Subscriptions.topics(sourceTopic))
    .groupedWithin(maxWindowWidth, masWindowPeriod)
    .map { windowRecords =>
      aggregate(windowRecords)
    } // aggregate and transform to multi message for producer
    .via(Producer.flexiFlow(producerSettings)) // flexiFlow accepts single or multi message
    .map(_.passThrough) // use passThrough property of produced message for committable offset - contains last consumed message committableOffset
    .toMat(Committer.sink(committerSettings))(Keep.both) // commit the offset and keep consumer control and stream completion materialized value
    .mapMaterializedValue { case (control, future) => DrainingControl.apply(control, future.toScala) }

  override def preStart(): Unit = {
    super.preStart()
    self ! Start
  }

  override def receive: Receive = {
    case Start =>
      // kafkacat -b localhost:9092 -t homework-output -e -o 33 -f "%k %s\n"
      val control = aggregator.run
      context.become(consuming(control))
  }

  protected def consuming(control: DrainingControl[Done]): Receive = {
    case Stop =>
      log.info("Stopping stream consumer")
      control.drainAndShutdown()(context.dispatcher)
      context.become(receive)
  }

  /**
    * JSON in messages is queried ad-hoc through string keys
    * as there is no schema to decode events to domain objects
    *
    * using example input it returns
    * 0 records for taskId: jNazVPlL11HFhTGs1_dc1
    * 3 records for taskId: TyazVPlL11HYaTGs1_dc1
    * cat src/main/resources/kafka-messages.jsonline |\
    * grep TyazVPlL11HYaTGs1_dc1 | grep '\\"confirmedLevel\\" : 1' | grep '\\"levels\\" : \[1\]' | wc -l
    */
  private def aggregate(
    messages: Seq[ConsumerMessage.CommittableMessage[String, Array[Byte]]]
  ): ProducerMessage.Envelope[String, Array[Byte], ConsumerMessage.CommittableOffset] = {
    val productRecords: Seq[ProducerRecord[String, Array[Byte]]] =
      messages
        .flatMap(
          msg =>
            // filter all op=="c" and parse json in "after" property
            parse(new String(msg.record.value))
              .getOrElse(Json.Null)
              .asObject
              .filter(o => o.apply("op").contains(Json.fromString("c")))
              .flatMap(o => o.apply("after"))
              .flatMap(after => parse(after.asString.getOrElse("")).toOption)
              .flatMap(afterJson => afterJson.asObject)
        )
        // group by taskId
        .groupBy(o => o.apply("taskId"))
        .toSeq
        .flatMap {
          case (Some(taskId), list) if taskId.isString =>
            // count confirmed, where levels.head == confirmedLevel
            val confirmedCount =
              list.count(
                o =>
                  o.apply("tUnits").getOrElse(Json.Null).asArray.exists { tUnits =>
                    tUnits.flatMap(tUnit => tUnit.asObject).map(o => o.apply("confirmedLevel")).head.exists {
                      confirmedLevel =>
                        o.apply("levels").exists(levels => levels.asArray.exists(l => l.head == confirmedLevel))
                    }
                  }
              )

            val tid = taskId.asString.get // safe "get "thanks partially defined at "if taskId.isString"
            log.debug(s"TaskId: $tid, Confirmed count: $confirmedCount")
            Some((tid, confirmedCount))
          case _ => None
        }
        .map {
          case (taskId, count) =>
            // for purposes of listing the output topic the int number is stored as string
            // though optimized it would be stored as 4 bytes: ByteBuffer.allocate(4).putInt(count).array
            new ProducerRecord(targetTopic, taskId, count.toString.getBytes)
        }

    ProducerMessage.multi(records = productRecords, passThrough = messages.last.committableOffset)
  }
}
