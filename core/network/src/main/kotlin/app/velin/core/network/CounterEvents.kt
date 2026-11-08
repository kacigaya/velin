package app.velin.core.network

import app.velin.core.sdk.TutaRelease
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONObject

/** One account per instance. No request, URL, response, or exception is logged. */
class CounterEvents internal constructor(
    private val scope: CoroutineScope,
    private val connect: (EventListener) -> AutoCloseable,
    private val retry: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    constructor(origin: URI, user: String, token: String) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), socketConnector(origin, user, token))

    private val lock = Any()
    private var closed = false
    private var socket: AutoCloseable? = null
    private var job: Job? = null
    private val counts = mutableMapOf<Pair<String, String>, Long>()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    val changes: kotlinx.coroutines.flow.Flow<Unit> get() = updates.receiveAsFlow()

    fun unread(group: String, folder: String): Long = synchronized(lock) { counts[group to folder] ?: 0 }

    fun start() = synchronized(lock) {
        check(!closed)
        if (job != null) return@synchronized
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val ended = CompletableDeferred<Unit>()
                val listener = object : EventListener {
                    override fun text(text: String) {
                        val update = try { parseCounterEvent(text) } catch (_: Exception) { ended.complete(Unit); return }
                        if (update != null) synchronized(lock) {
                            if (!closed && !ended.isCompleted) {
                                // Bound state even if a peer supplies arbitrary group/folder IDs.
                                if (counts.size + update.values.size > 4096) counts.clear()
                                update.values.forEach { (folder, count) -> counts[update.group to folder] = count }
                                updates.trySend(Unit)
                            }
                        }
                    }
                    override fun end() { ended.complete(Unit) }
                }
                val connection = try { connect(listener) } catch (_: Exception) { null }
                synchronized(lock) { if (closed) connection?.close() else socket = connection }
                try { if (connection != null) ended.await() }
                finally {
                    synchronized(lock) {
                        if (socket === connection) { connection?.close(); socket = null }
                        counts.clear()
                        updates.trySend(Unit)
                    }
                }
                retry(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            job?.cancel()
            socket?.close(); socket = null
            counts.clear()
            updates.close()
            scope.cancel()
        }
    }
}

internal interface EventListener {
    fun text(text: String)
    fun end()
}
internal data class CounterUpdate(val group: String, val values: Map<String, Long>)
internal const val MAX_EVENT_BYTES = 256 * 1024

// EventBusClient.ts:330-381; sys/TypeModels.js:9780-9858. Numeric keys may have name suffixes.
internal fun parseCounterEvent(message: String): CounterUpdate? {
    require(message.length <= MAX_EVENT_BYTES && message.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES)
    if (!message.startsWith("unreadCounterUpdate;")) return null
    val json = message.substringAfter(';')
    var depth = 0
    var string = false
    var escaped = false
    for (ch in json) {
        if (string) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') string = false
        } else when (ch) {
            '"' -> string = true
            '{', '[' -> { depth++; require(depth <= 8) }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
    }
    require(depth == 0 && !string)
    fun JSONObject.field(id: String): String = keys().asSequence().filter { it.substringBefore(':') == id }.single()
    fun JSONObject.id(id: String): String = getString(field(id)).also {
        require(it.isNotEmpty() && it.length <= 340 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' })
    }
    val root = JSONObject(json)
    val group = root.id("1494")
    val rows = root.getJSONArray(root.field("1495"))
    require(rows.length() <= 2048)
    val values = mutableMapOf<String, Long>()
    repeat(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val folder = row.id("1490")
        val countText = row.get(row.field("1491")).toString()
        require(countText.matches(Regex("0|[1-9][0-9]{0,9}")))
        val count = countText.toLong().also { require(it <= Int.MAX_VALUE) }
        require(values.put(folder, count) == null)
    }
    return CounterUpdate(group, values)
}

private fun socketConnector(origin: URI, user: String, token: String): (EventListener) -> AutoCloseable {
    require(origin.scheme == "https" && origin.host != null && origin.userInfo == null && origin.query == null && origin.fragment == null)
    // EventBusClient.ts:209-255 supplies exactly these login parameters.
    val url = origin.toString().toHttpUrl().newBuilder().encodedPath("/event")
        .addQueryParameter("modelVersions", "${TutaRelease.SYS_MODEL_VERSION}.${TutaRelease.TUTANOTA_MODEL_VERSION}")
        .addQueryParameter("clientVersion", TutaRelease.CLIENT_VERSION)
        .addQueryParameter("userId", user).addQueryParameter("accessToken", token).build()
    return { listener ->
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .pingInterval(30, TimeUnit.SECONDS).build()
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_EVENT_BYTES) { webSocket.cancel(); listener.end() }
                else listener.text(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel(); listener.end() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.cancel(); listener.end() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { listener.end() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runCatching { response?.close() }
                listener.end()
            }
        })
        AutoCloseable {
            socket.cancel()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
