# Eclair Nostr Bot Plugin

This is an [Eclair](https://github.com/ACINQ/eclair) plugin that sends notifications in the form of NIP-04 encrypted direct messages to the node operator(s) via a Nostr relay.

## How to build

First you need to build its dependencies

```bash
git clone https://github.com/ACINQ/eclair.git

cd eclair/

git checkout v0.8.0

mvn install -DskipTests=true
```

Then build the plugin
```bash
git clone https://github.com/rorp/eclair-nostr-bot.git

cd eclair-nostr-bot/

mvn install
```

The last `mvn` command will put the plugin's JAR file into `target` directory. 

## Hot to run

Simply add the JAR file name to the Eclair node command line:

```bash
<PATH_TO_YOUR_ECLAIR_INSTALLATION>/eclair-node.sh target/eclair-nostr-bot_2.13-0.8.0.jar
```

## Configuration

In eclair.conf add:

| Config Parameter       | Description                                                                                                                             | Default Value |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|---------------|
| nostr.alarmbot.relay           | A URL of a Nostr relay. Consider using a private relay or a public one that supports NIP-42.                                            |               |
| nostr.alarmbot.seckey          | An optional bot's private key in NIP-19 format. If absent the bot will generate a private key and store it in `nostralarmbot.key` file. |               |
| nostr.alarmbot.receivers       | A list of receivers' public keys in NIP-19 format.                                                                                      |               |
| nostr.alarmbot.eventExpiration | An optional event expiration duration.                                                                                                  | never expire  |
| nostr.alarmbot.useProxy        | Use SOCKS5 proxy if the Eclair node is configured to use it.                                                                            | true          |
