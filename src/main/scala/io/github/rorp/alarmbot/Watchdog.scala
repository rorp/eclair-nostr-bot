package io.github.rorp.alarmbot

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor.{ZMQConnected, ZMQDisconnected, ZMQEvent}
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.DangerousBlocksSkew
import fr.acinq.eclair.channel._
import fr.acinq.eclair.{Kit, NotificationsLogger}
import io.github.rorp.alarmbot.Messenger.SendMessage
import io.github.rorp.alarmbot.NostrAlarmBotPlugin.LOG_PREFIX

object Watchdog {

  sealed trait Command

  case class WrappedDangerousBlocksSkew(msg: DangerousBlocksSkew) extends Command

  case class WrappedChannelStateChanged(msg: ChannelStateChanged) extends Command

  case class WrappedChannelClosed(msg: ChannelClosed) extends Command

  case class WrappedZMQEvent(msg: ZMQEvent) extends Command

  case class WrappedNotifyNodeOperator(msg: NotifyNodeOperator) extends Command

  def apply(kit: Kit, pluginConfig: Config, messenger: ActorRef[Messenger.Command]): Behavior[Command] = {
    def sendMessage(message: String, tag: String): Behavior[Command] = {
      messenger ! SendMessage(message, tag)
      Behaviors.same
    }

    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[DangerousBlocksSkew](e => WrappedDangerousBlocksSkew(e)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelStateChanged](e => WrappedChannelStateChanged(e)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelClosed](e => WrappedChannelClosed(e)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ZMQEvent](e => WrappedZMQEvent(e)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NotificationsLogger.NotifyNodeOperator](e => WrappedNotifyNodeOperator(e)))

      context.log.info(s"$LOG_PREFIX pubkey=${pluginConfig.seckey.publicKey.toBech32} ${pluginConfig.seckey.publicKey.toHex}")

      messenger ! Messenger.Connect

      sendMessage(s"Node is starting\nAlias: ${kit.nodeParams.alias}\nNodeId: ${kit.nodeParams.nodeId}\nInstanceId: ${kit.nodeParams.instanceId} ", "preStart")

      Behaviors.receiveMessagePartial {
        case WrappedChannelStateChanged(ChannelStateChanged(_, channelId, _, remoteNodeId, WAIT_FOR_CHANNEL_READY | WAIT_FOR_DUAL_FUNDING_READY, NORMAL, commitsOpt)) =>
          val details = commitsOpt.map(commits => s"capacity: ${commits.latest.capacity}, announceChannel: ${commits.announceChannel}")
          sendMessage(s"New channel established, remoteNodeId: $remoteNodeId, channelId: $channelId, ${details.orNull}", "ChannelStateChanged")

        case WrappedChannelClosed(ChannelClosed(_, channelId, closingType, _)) =>
          sendMessage(s"Channel closed, channelId: $channelId, closingType: ${closingType.getClass.getName}", "ChannelClosed")

        case WrappedZMQEvent(ZMQConnected) =>
          sendMessage("ZMQ connection UP", "ZMQConnected")

        case WrappedZMQEvent(ZMQDisconnected) =>
          sendMessage("ZMQ connection DOWN", "ZMQDisconnected")

        case WrappedDangerousBlocksSkew(msg) =>
          sendMessage(s"DangerousBlocksSkew from ${msg.recentHeaders.source}", "DangerousBlocksSkew")

        case WrappedNotifyNodeOperator(NotifyNodeOperator(severity, message)) =>
          val prefix = severity match {
            case NotificationsLogger.Info => ""
            case NotificationsLogger.Warning => "WARNING: "
            case NotificationsLogger.Error => "ERROR: "
          }
          sendMessage(s"$prefix$message", "NotifyNodeOperator")
      }
    }
  }
}
