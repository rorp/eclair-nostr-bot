package io.github.rorp.alarmbot

import akka.actor.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.eclair.tor.Socks5ProxyParams
import io.github.rorp.alarmbot.Messenger._
import io.github.rorp.alarmbot.NostrAlarmBotPlugin.LOG_PREFIX
import org.slf4j.Logger
import snostr.client.akkahttp.AkkaHttpNostrClient
import snostr.client.akkahttp.AkkaHttpNostrClient.TimeoutException
import snostr.codec.jackson.JacksonCodecs
import snostr.core._

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.DurationDouble
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Messenger {
  sealed trait Command

  case class SendMessage(message: String, tag: String) extends Command

  case class SendEvent(event: NostrEvent, tag: String) extends Command

  case class ProcessRelayMessage(relayMessage: NostrRelayMessage) extends Command

  case class Connected(nostrClient: NostrClient) extends Command

  case object Connect extends Command

  case object Reconnect extends Command

  def apply(config: Config, socks5ProxyParams: Option[Socks5ProxyParams]): Behavior[Command] = {
    Behaviors.setup { context =>
      new Messenger(config, socks5ProxyParams, context).waiting
    }
  }
}

class Messenger private(config: Config, socks5ProxyParams: Option[Socks5ProxyParams], context: ActorContext[Command]) {

  private val retires = new AtomicInteger(0)
  private val events = mutable.UnrolledBuffer[SendEvent]()

  private implicit val codecs: Codecs = JacksonCodecs

  def debug(msg: => String, log: Logger): Unit = {
    if (log.isDebugEnabled()) {
      log.debug(s"$LOG_PREFIX $msg");
    }
  }

  def debug(msg: => String): Unit = {
    debug(msg, context.log)
  }

  def info(msg: => String, log: Logger): Unit = {
    if (log.isInfoEnabled()) {
      log.info(s"$LOG_PREFIX $msg")
    }
  }

  def info(msg: => String): Unit = {
    info(msg, context.log)
  }

  def warn(msg: => String, log: Logger): Unit = {
    if (log.isWarnEnabled()) {
      log.warn(s"$LOG_PREFIX $msg");
    }
  }

  def warn(msg: => String): Unit = {
    warn(msg, context.log)
  }

  def error(msg: => String, log: Logger): Unit = {
    if (log.isErrorEnabled()) {
      log.error(s"$LOG_PREFIX $msg");
    }
  }

  def error(msg: => String): Unit = {
    error(msg, context.log)
  }


  def waiting: Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case Connect =>
        connect(context.system.classicSystem)
        connecting
      case SendMessage(message, tag) =>
        debug(s"message to stash: $message")
        stashEvents(message, tag)
        Behaviors.same
    }
  }

  def connecting: Behavior[Command] = {
    implicit val ec = context.executionContext
    Behaviors.receiveMessagePartial {
      case Connected(nostrClient) =>
        events.foreach(e => context.self ! e)
        events.clear()
        retires.set(0)
        connected(nostrClient)
      case Reconnect =>
        reconnect
      case SendMessage(message, tag) =>
        debug(s"message to send: $message ")
        stashEvents(message, tag)
        Behaviors.same
    }
  }

  def connected(nostrClient: NostrClient): Behavior[Command] = {
    implicit val ec = context.executionContext
    val log = context.log
    nostrClient.relayInformation(Vector()).foreach(relayInfo => info(s"relay info: $relayInfo", log))
    Behaviors.receiveMessagePartial {
      case SendEvent(dm, tag) =>
        debug(s"event to send: $dm ")
        publish(nostrClient, dm, tag)
        Behaviors.same
      case SendMessage(message, tag) =>
        debug(s"message to send: $message ")
        config.receivers.foreach { pubkey =>
          val dm = NostrEvent.encryptedDirectMessage(config.seckey, message, pubkey, expiration = expiration)(JacksonCodecs)
          publish(nostrClient, dm, tag)
        }
        Behaviors.same
      case ProcessRelayMessage(relayMessage) =>
        processRelayMessage(nostrClient, relayMessage)
        Behaviors.same
      case Reconnect =>
        reconnect
    }
  }

  def reconnect(implicit ec: ExecutionContext): Behavior[Command] = {
    val delay = {
      val r = retires.get()
      if (r == 0) {
        retires.addAndGet(1)
        0.millis
      } else {
        val d = (config.initialReconnectDelay.toMillis * (1 << r - 1)).millis
        if (d > config.maxReconnectDelay) {
          config.maxReconnectDelay
        } else {
          retires.addAndGet(1)
          d
        }
      }
    }
    if (delay > 0.millis) {
      info(s"next connection attempt in $delay")
    }
    val self = context.self
    context.system.scheduler.scheduleOnce(delay, () => self ! Connect)
    waiting
  }

  private def expiration: Option[Instant] = config.eventExpiration.map(d => Instant.now().plusMillis(d.toMillis))

  private def publish(nostrClient: NostrClient, dm: NostrEvent, tag: String)(implicit ec: ExecutionContext): Unit = {
    val log = context.log
    val to = dm.kind match {
      case EncryptedDirectMessage(_, receiverPublicKey, _, _, _) => s" to ${receiverPublicKey.toBech32} ${receiverPublicKey.toHex}"
      case _ => ""
    }
    debug(s"sending message ${dm.id.toHex} $to", log)
    nostrClient.publish(dm).map(_ => dm.id).onComplete {
      case Success(id) => info(s"sent ${id.toHex} '$tag'", log)
      case Failure(reason) =>
        error(s"failed to send '$tag', reason: ${reason.getMessage}", log)
    }
  }

  private def connect(implicit system: ActorSystem): Future[Unit] = {
    implicit val ec: ExecutionContext = system.dispatcher
    val (socks5Url, socks5User, socks5Pass) = socks5ProxyParams match {
      case Some(proxyParams) if config.useProxy =>
        val socks5Url = Some(s"tcp://${proxyParams.address.getHostString}:${proxyParams.address.getPort}")
        val (socks5User, socks5Pass) = Socks5ProxyParams.proxyCredentials(proxyParams) match {
          case Some(value) => (Some(value.username), Some(value.password))
          case None => (None, None)
        }
        (socks5Url, socks5User, socks5Pass)
      case _ =>
        (None, None, None)
    }

    info(s"connecting to ${config.relay}${if (socks5Url.nonEmpty) " via " + socks5Url.get else ""}")

    val client = new AkkaHttpNostrClient(
      url = config.relay,
      socks5Proxy = socks5Url,
      socks5Username = socks5User,
      socks5Password = socks5Pass,
      connectionTimeout = config.connectionTimeout,
      keepAliveMaxIdle = config.keepAliveMaxIdle)

    val log = context.log

    client.addRelayMessageCallback(msg => Future.successful(context.self ! ProcessRelayMessage(msg)))
    client.addDisconnectionCallback { () =>
      info(s"disconnected from ${config.relay}", log)
      Future.successful(context.self ! Reconnect)
    }

    val res = client.connect()

    val self = context.self

    res.onComplete {
      case Success(_) =>
        info(s"connected to ${config.relay}", log)
        self ! Connected(client)
      case Failure(exception) =>
        error(s"cannot connect to ${config.relay} $exception")
        exception match {
          case _: TimeoutException => self ! Reconnect
          case _ => ()
        }
    }

    res
  }

  private def stashEvents(message: String, tag: String): Unit = {
    val newEvents = config.receivers.map { pubkey =>
      SendEvent(NostrEvent.encryptedDirectMessage(config.seckey, message, pubkey, expiration = expiration)(JacksonCodecs), tag)
    }
    val diff = Math.max(0, events.size + newEvents.size - config.maxMessageBufferSize)
    events.remove(0, diff)
    events.addAll(newEvents)
    debug(s"stashed events $events")
  }

  private def processRelayMessage(nostrClient: NostrClient, relayMessage: NostrRelayMessage)(implicit ec: ExecutionContext): Unit = {
    relayMessage match {
      case auth: AuthRelayMessage =>
        warn(s"unexpected AUTH message $auth")
      case eose: EndOfStoredEventsRelayMessage =>
        warn(s"unexpected EOSE message $eose")
      case event: EventRelayMessage =>
        warn(s"unexpected EVENT message $event")
      case NoticeRelayMessage(message) =>
        warn(s"notice: $message")
      case OkRelayMessage(eventId, result) =>
        result match {
          case OkRelayMessage.Saved(msg) =>
            info(s"message ${eventId.toHex} saved $msg")
          case rejected: OkRelayMessage.Rejected =>
            error(s"message ${eventId.toHex} rejected: ${rejected.message}")
        }
      case _ => ()
    }
  }
}
