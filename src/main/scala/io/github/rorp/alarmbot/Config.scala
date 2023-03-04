package io.github.rorp.alarmbot

import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.ceedubs.ficus.Ficus._
import snostr.core.{NostrPrivateKey, NostrPublicKey}

import java.io.File
import java.nio.file.{Files, StandardOpenOption}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

case class Config(datadir: File) {

  val ConfigFileName = "nostralarmbot.conf"

  val KeyFileName = "nostralarmbot.key"

  val resourcesDir: File = new File(datadir, "/plugin-resources/nostralarmbot/")

  val config: TypesafeConfig = ConfigFactory.parseFile(new File(resourcesDir, ConfigFileName))
    .resolve()

  val relay: String = config.as[String]("config.relay")

  val useProxy: Boolean = config.as[Option[Boolean]]("config.useProxy").getOrElse(true)

  val receivers: Seq[NostrPublicKey] = config.as[List[String]]("config.receivers").map(Config.parsePubkey)

  val eventExpiration: Option[FiniteDuration] = config.as[Option[FiniteDuration]]("config.eventExpiration")

  val connectionTimeout: FiniteDuration = config.as[Option[FiniteDuration]]("config.connectionTimeout")
    .getOrElse(60.seconds)

  val keepAliveMaxIdle: FiniteDuration = config.as[Option[FiniteDuration]]("config.keepAliveMaxIdle")
    .getOrElse(30.seconds)

  val maxMessageBufferSize: Int = config.as[Option[Int]]("config.maxMessageBufferSize")
    .getOrElse(128)

  val initialReconnectDelay: FiniteDuration = config.as[Option[FiniteDuration]]("config.initialReconnectDelay")
    .getOrElse(100.millis)

  val maxReconnectDelay: FiniteDuration = config.as[Option[FiniteDuration]]("config.maxReconnectDelay")
    .getOrElse(10.minutes)

  val seckey: NostrPrivateKey = config.as[Option[String]]("config.seckey").map(Config.parseSeckey) match {
    case Some(key) => key
    case None =>
      val keyFile = new File(resourcesDir, KeyFileName)
      if (keyFile.exists()) {
        val nsec = Files.readString(keyFile.toPath)
        Config.parseSeckey(nsec)
      } else {
        val key = NostrPrivateKey.freshPrivateKey
        Files.writeString(keyFile.toPath, key.toBech32, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        key
      }
  }
}

object Config {
  def parseSeckey(value: String): NostrPrivateKey = try {
    Try(NostrPrivateKey.fromHex(value)).getOrElse(NostrPrivateKey.fromBech32(value))
  } catch {
    case e: Throwable => throw new IllegalArgumentException(s"cannot parse a Nostr private key (hex or bech32): ${e.getMessage}")
  }

  def parsePubkey(value: String): NostrPublicKey = try {
    Try(NostrPublicKey.fromHex(value)).getOrElse(NostrPublicKey.fromBech32(value))
  } catch {
    case e: Throwable => throw new IllegalArgumentException(s"cannot parse a Nostr public key (hex or bech32): ${e.getMessage}")
  }
}