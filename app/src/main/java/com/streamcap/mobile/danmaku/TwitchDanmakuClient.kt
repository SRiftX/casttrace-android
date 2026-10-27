package com.streamcap.mobile.danmaku

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import kotlin.random.Random

class TwitchDanmakuClient(client: OkHttpClient) : WebSocketChatClient(client) {
    override suspend fun prepare(context: DanmakuConnectContext): ChatConnection {
        val url = requireNotNull(context.roomUrl.toHttpUrlOrNull())
        require(url.host == "twitch.tv" || url.host == "www.twitch.tv")
        val channel = url.pathSegments.first().lowercase().also { require(it.matches(Regex("[a-z0-9_]{1,25}"))) }
        return object : ChatConnection {
            override val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443")
                .header("Origin", "https://www.twitch.tv").build()
            override fun opened(socket: WebSocket) {
                socket.send("CAP REQ :twitch.tv/tags twitch.tv/commands\r\n")
                socket.send("PASS SCHMOOPIIE\r\n")
                socket.send("NICK justinfan${Random.nextInt(10000, 100000)}\r\n")
                socket.send("JOIN #$channel\r\n")
            }
            override fun text(socket: WebSocket, text: String, emit: (DanmakuFrame) -> Unit) {
                text.split("\r\n").filter(String::isNotEmpty).forEach { line ->
                    if (line.startsWith("PING ")) socket.send("PONG ${line.substring(5)}\r\n")
                    else if (line.contains(" RECONNECT")) socket.close(1000, "server-reconnect")
                    else if (line.contains(" ROOMSTATE #$channel")) emit(DanmakuFrame.Status("subscribed-real"))
                    else parseLine(line, channel, context, android.os.SystemClock.elapsedRealtime())?.let(emit)
                }
            }
        }
    }

    companion object {
        internal fun parseLine(line: String, channel: String, context: DanmakuConnectContext, elapsedMs: Long): DanmakuFrame? {
            require(line.length <= 1024 * 1024)
            var body = line
            val tags = if (body.startsWith('@')) {
                val end = body.indexOf(' ').takeIf { it > 0 } ?: return null
                val values = body.substring(1, end).split(';').mapNotNull { tag ->
                    val equals = tag.indexOf('='); if (equals < 0) null else tag.substring(0, equals) to unescape(tag.substring(equals + 1))
                }.toMap()
                body = body.substring(end + 1)
                values
            } else emptyMap()
            val match = Regex("^:([^! ]+)(?:![^ ]+)? PRIVMSG #([A-Za-z0-9_]+) :(.*)$").matchEntire(body) ?: return null
            if (!match.groupValues[2].equals(channel, true)) return null
            val text = match.groupValues[3].takeIf(String::isNotBlank) ?: return null
            return ChatEventFactory.event(context, "twitch", text, tags["id"], tags["user-id"],
                tags["display-name"]?.takeIf(String::isNotBlank) ?: match.groupValues[1],
                sourceTimeMs = tags["tmi-sent-ts"]?.toLongOrNull(), elapsedMs = elapsedMs)
        }

        private fun unescape(value: String): String = buildString {
            var i = 0
            while (i < value.length) {
                if (value[i] == '\\' && i + 1 < value.length) {
                    append(when (val ch = value[++i]) { 's' -> ' '; ':' -> ';'; 'r' -> '\r'; 'n' -> '\n'; else -> ch })
                } else append(value[i])
                i++
            }
        }
    }
}
