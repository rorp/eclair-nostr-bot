import sbt._

object Dependencies {
  lazy val snostrCore = "io.github.rorp" %% "snostr-core" % "0.1.0-SNAPSHOT"
  lazy val snostrCodecJackson = "io.github.rorp" %% "snostr-codec-jackson" % "0.1.0-SNAPSHOT"
  lazy val snostrClientAkkaHttp = "io.github.rorp" %% "snostr-client-akka-http" % "0.1.0-SNAPSHOT"

  lazy val eclairCore = "fr.acinq.eclair" %% "eclair-core" % "0.8.0"
  lazy val eclairNode = "fr.acinq.eclair" %% "eclair-node" % "0.8.0"

  lazy val ficus = "com.iheart" %% "ficus" % "1.5.2"
}
