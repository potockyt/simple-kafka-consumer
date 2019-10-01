package works.samazama.consumer

import java.util.concurrent.CountDownLatch

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import scala.util.Try

object Boot extends App {
  private val defaultBootstrap = "localhost:9092"
  private val defaultGroup = "consumerGroup1"
  private val defaultSource = "homework"
  private val defaultTarget = "homework-output"

  private val usage =
    s"""
    Usage:
    sbt run --bootstrap list-of-servers --group group-id --source source-topic --target target-topic
    """

  type ArgMap = Map[String, String]

  if (args.length == 1 && args(0) == "--help") {
    println(usage)
    sys.exit(0)
  }

  @scala.annotation.tailrec
  def nextArg(argMap: ArgMap, list: List[String]): Either[IllegalArgumentException, ArgMap] = {
    list match {
      case Nil                            => Right(argMap)
      case "--bootstrap" :: value :: tail => nextArg(argMap ++ Map("bootstrap" -> value), tail)
      case "--group" :: value :: tail     => nextArg(argMap ++ Map("group" -> value), tail)
      case "--source" :: value :: tail    => nextArg(argMap ++ Map("source" -> value), tail)
      case "--target" :: value :: tail    => nextArg(argMap ++ Map("target" -> value), tail)
      case option :: _ =>
        Left(new IllegalArgumentException("Unknown option " + option))

    }
  }

  private val setupArgs = nextArg(Map(), args.toList).fold(ex => throw ex, m => m)

  private implicit val system: ActorSystem = ActorSystem("assignment-system")
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  system.actorOf(
    EventConsumer.props(
      servers = setupArgs.getOrElse("bootstrap", defaultBootstrap),
      groupId = setupArgs.getOrElse("group", defaultGroup),
      sourceTopic = setupArgs.getOrElse("source", defaultSource),
      targetTopic = setupArgs.getOrElse("target", defaultTarget)
    )
  )

  private val countDownLatch = new CountDownLatch(1)
  sys.addShutdownHook {
    println("Shutting down actor system")
    system.terminate()
    ()
  }
  Try {
    println("Started")
    countDownLatch.await()
  }.getOrElse(sys.exit(1))
}
