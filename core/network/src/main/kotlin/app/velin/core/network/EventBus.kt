package app.velin.core.network

import app.velin.core.sdk.TutaRelease
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONObject

sealed interface SyncEvent {
    /** With [replay], the server sends the batches after the given cursors, then [CaughtUp]. */
    data class Connected(val replay: Boolean) : SyncEvent
    data class Batch(val group: String, val id: String, val changes: List<EntityChange>) : SyncEvent
    data object CaughtUp : SyncEvent
}
enum class EntityOperation { Create, Update, Delete }
/** Metadata only. Encrypted instance data in the update is not read. */
data class EntityChange(val application: String, val type: Long, val list: String?, val element: String,
    val operation: EntityOperation)

/**
 * One account per instance. No request, URL, response, or exception is logged.
 * [cursors] returns the last durably applied batch ID per group. It runs before each connect.
 */
class EventBus internal constructor(
    private val scope: CoroutineScope,
    private val connect: (Map<String, String>, EventListener) -> AutoCloseable,
    private val cursors: suspend () -> Map<String, String>,
    private val retry: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    constructor(origin: URI, user: String, token: String, cursors: suspend () -> Map<String, String>) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), socketConnector(origin, user, token), cursors)

    private val lock = Any()
    private var closed = false
    private var socket: AutoCloseable? = null
    private var job: Job? = null
    private val counts = mutableMapOf<Pair<String, String>, Long>()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    // A full queue ends the connection. The reconnect replays from the last durable cursor.
    private val queue = Channel<SyncEvent>(EVENT_QUEUE)
    val changes: Flow<Unit> get() = updates.receiveAsFlow()
    /** Ordered. Collect from one coroutine only. */
    val events: Flow<SyncEvent> get() = queue.receiveAsFlow()

    fun unread(group: String, folder: String): Long = synchronized(lock) { counts[group to folder] ?: 0 }

    fun start() = synchronized(lock) {
        check(!closed)
        if (job != null) return@synchronized
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val known = try { cursors() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
                val ended = CompletableDeferred<Unit>()
                fun deliver(event: SyncEvent) = synchronized(lock) {
                    if (!closed && !ended.isCompleted && queue.trySend(event).isFailure) ended.complete(Unit)
                }
                val listener = object : EventListener {
                    override fun open() {
                        backoff = 1_000L
                        deliver(SyncEvent.Connected(known.isNotEmpty()))
                    }
                    override fun text(text: String) {
                        val message = try { parseEventMessage(text) } catch (_: Exception) { ended.complete(Unit); return }
                        when (message) {
                            is SyncEvent -> deliver(message)
                            is CounterUpdate -> synchronized(lock) {
                                if (!closed && !ended.isCompleted) {
                                    // Bound state even if a peer supplies arbitrary group/folder IDs.
                                    if (counts.size + message.values.size > 4096) counts.clear()
                                    message.values.forEach { (folder, count) -> counts[message.group to folder] = count }
                                    updates.trySend(Unit)
                                }
                            }
                        }
                    }
                    override fun end() { ended.complete(Unit) }
                }
                val connection = try { connect(known, listener) } catch (_: Exception) { null }
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
            queue.close()
            scope.cancel()
        }
    }
}

internal interface EventListener {
    fun open() {}
    fun text(text: String)
    fun end()
}
internal data class CounterUpdate(val group: String, val values: Map<String, Long>)
// Entity updates can carry an encrypted mail details blob. OkHttp closes larger frames itself.
internal const val MAX_EVENT_BYTES = 16 * 1024 * 1024
internal const val EVENT_QUEUE = 256
private val ID = Regex("[A-Za-z0-9_-]{1,340}")

/** Returns [SyncEvent], [CounterUpdate], or null for message types Velin does not use. */
internal fun parseEventMessage(message: String): Any? {
    require(message.length <= MAX_EVENT_BYTES)
    return when (message.substringBefore(';')) {
        "entityUpdate" -> parseEntityEvent(message)
        "unreadCounterUpdate" -> parseCounterEvent(message)
        "initialSyncDone" -> SyncEvent.CaughtUp
        else -> null
    }
}

// EventBusClient.ts:330-381; sys/TypeModels.js:9780-9858. Numeric keys may have name suffixes.
internal fun parseCounterEvent(message: String): CounterUpdate? {
    if (!message.startsWith("unreadCounterUpdate;")) return null
    val root = boundedJson(message.substringAfter(';'))
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

// EventBusClient.ts:330-366. WebsocketEntityData 1483 and EntityUpdate 462 in sys.json.
internal fun parseEntityEvent(message: String): SyncEvent.Batch? {
    if (!message.startsWith("entityUpdate;")) return null
    val root = boundedJson(message.substringAfter(';'))
    val rows = root.getJSONArray(root.field("1487"))
    require(rows.length() <= 4096)
    val changes = List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val list = row.getString(row.field("466"))
        EntityChange(row.getString(row.field("464")).also { require(it.matches(Regex("[a-z]{1,32}"))) },
            row.get(row.field("2556")).toString().also { require(it.matches(Regex("[1-9][0-9]{0,8}"))) }.toLong(),
            if (list.isEmpty()) null else list.also { require(it.matches(ID)) }, row.id("467"),
            when (row.get(row.field("624")).toString()) {
                "0" -> EntityOperation.Create
                "1" -> EntityOperation.Update
                "2" -> EntityOperation.Delete
                else -> throw IllegalArgumentException("Unknown operation")
            })
    }
    return SyncEvent.Batch(root.id("1486"), root.id("1485"), changes)
}

private fun boundedJson(json: String): JSONObject {
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES)
    var depth = 0
    var string = false
    var escaped = false
    for (ch in json) {
        if (string) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') string = false
        } else when (ch) {
            '"' -> string = true
            '{', '[' -> { depth++; require(depth <= 16) }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
    }
    require(depth == 0 && !string)
    return JSONObject(json)
}
private fun JSONObject.field(id: String): String = keys().asSequence().filter { it.substringBefore(':') == id }.single()
private fun JSONObject.id(id: String): String = getString(field(id)).also { require(it.matches(ID)) }

private fun socketConnector(origin: URI, user: String, token: String): (Map<String, String>, EventListener) -> AutoCloseable {
    require(origin.scheme == "https" && origin.host != null && origin.userInfo == null && origin.query == null && origin.fragment == null)
    // EventBusClient.ts:209-255 supplies exactly these login parameters.
    val base = origin.toString().toHttpUrl().newBuilder().encodedPath("/event")
        .addQueryParameter("modelVersions", "${TutaRelease.SYS_MODEL_VERSION}.${TutaRelease.TUTANOTA_MODEL_VERSION}")
        .addQueryParameter("clientVersion", TutaRelease.CLIENT_VERSION)
        .addQueryParameter("userId", user).addQueryParameter("accessToken", token).build()
    return { cursors, listener ->
        val url = if (cursors.isEmpty()) base else {
            require(cursors.size <= 64 && cursors.all { (group, batch) -> group.matches(ID) && batch.matches(ID) })
            // EventBusClient.ts:228-245 sends "group=batch;" pairs unencoded. The ID check keeps them URL-safe.
            base.newBuilder().addEncodedQueryParameter("groupsToLastEventBatchIds",
                cursors.entries.joinToString("") { (group, batch) -> "$group=$batch;" }).build()
        }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .pingInterval(30, TimeUnit.SECONDS).build()
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { listener.open() }
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
