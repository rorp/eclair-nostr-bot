package io.github.rorp.alarmbot

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import fr.acinq.eclair.{Kit, Plugin, PluginParams, Setup}
import grizzled.slf4j.Logging

class NostrAlarmBotPlugin extends Plugin with Logging {
  var pluginConfig: Config = _

  override def params: PluginParams = new PluginParams {
    override def name: String = "NostrAlarmBot"
  }

  override def onSetup(setup: Setup): Unit = {
    pluginConfig = new Config(setup.config, setup.datadir)
  }

  override def onKit(kit: Kit): Unit = {
    val messenger = kit.system.spawn(Behaviors.supervise(Messenger(pluginConfig, kit.nodeParams.socksProxy_opt)).onFailure(SupervisorStrategy.restart), name = "messenger")
    kit.system.spawn(Behaviors.supervise(Watchdog(kit, pluginConfig, messenger)).onFailure(SupervisorStrategy.restart), name = "watchdog")
  }
}

object NostrAlarmBotPlugin {
  val LOG_PREFIX = "PLGN NostrAlarmBot,"
}
