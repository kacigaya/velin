package app.velin.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class EventBusTest {
    private fun update(count: String = "7", folder: String = "folder") =
        """unreadCounterUpdate;{"1494:mailGroup":"group","1495:counterValues":[{"1490":"$folder","1491":"$count"}]}"""
    private fun batch(id: String = "batch", operation: String = "2", list: String = "mails") =
        """entityUpdate;{"1485:eventBatchId":"$id","1486":"group","1487":[{"464":"tutanota","2556":"97","466":"$list","467":"mail","624":"$operation","2617":"{\"x\":1}"}]}"""
    @Test fun decodeReleaseFieldsAndRejectInvalidCounts() {
        assertEquals(CounterUpdate("group", mapOf("folder" to 7L)), parseCounterEvent(update()))
        assertNull(parseCounterEvent("entityUpdate;ignored"))
        listOf("-1", "2147483648", "1.5", "1e3", "NaN").forEach { count ->
            assertTrue(runCatching { parseCounterEvent(update(count)) }.isFailure)
        }
        assertTrue(runCatching { parseEventMessage("x".repeat(MAX_EVENT_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { parseCounterEvent("unreadCounterUpdate;" + "[".repeat(100)) }.isFailure)
    }
    @Test fun decodeEntityBatchMetadataOnly() {
        assertEquals(SyncEvent.Batch("group", "batch", listOf(EntityChange("tutanota", 97, "mails", "mail", EntityOperation.Delete))),
            parseEventMessage(batch()))
        assertEquals(EntityChange("tutanota", 97, null, "mail", EntityOperation.Create),
            parseEntityEvent(batch(operation = "0", list = ""))!!.changes.single())
        assertEquals(SyncEvent.CaughtUp, parseEventMessage("initialSyncDone;"))
        assertNull(parseEventMessage("leaderStatus;{}"))
        listOf(batch(operation = "3"), batch(id = "a/b"), batch(list = "a=b;"), batch().replace("\"97\"", "\"-1\"")).forEach {
            assertTrue(it, runCatching { parseEventMessage(it) }.isFailure)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun eachConnectSendsCurrentCursorsAndReportsReplay() = runTest {
        val listeners = mutableListOf<EventListener>()
        val sent = mutableListOf<Map<String, String>>()
        var cursors = emptyMap<String, String>()
        val client = EventBus(backgroundScope, { known, listener -> sent += known; listeners += listener; AutoCloseable {} }, { cursors })
        val events = mutableListOf<SyncEvent>()
        backgroundScope.launch { client.events.toList(events) }
        client.start()
        runCurrent()
        listeners[0].open()
        listeners[0].text(batch("one"))
        runCurrent()
        cursors = mapOf("group" to "one")
        listeners[0].end()
        advanceTimeBy(1_000); runCurrent()
        listeners[1].open()
        listeners[1].text("initialSyncDone")
        listeners[0].text(batch("late"))
        runCurrent()
        assertEquals(listOf(emptyMap(), mapOf("group" to "one")), sent)
        assertEquals(listOf(SyncEvent.Connected(false), parseEntityEvent(batch("one")), SyncEvent.Connected(true), SyncEvent.CaughtUp), events)
        client.close()
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun fullQueueEndsConnection() = runTest {
        val listeners = mutableListOf<EventListener>()
        val client = EventBus(backgroundScope, { _, listener -> listeners += listener; AutoCloseable {} }, { emptyMap() })
        client.start()
        runCurrent()
        repeat(EVENT_QUEUE + 1) { listeners[0].text(batch("b$it")) }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, listeners.size)
        client.close()
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun reconnectAndCloseDiscardLateUpdates() = runTest {
        val listeners = mutableListOf<EventListener>()
        var closes = 0
        val client = EventBus(backgroundScope, { _, listener -> listeners += listener; AutoCloseable { closes++ } }, { emptyMap() })
        client.start()
        runCurrent()
        listeners[0].text(update())
        assertEquals(7L, client.unread("group", "folder"))
        assertEquals(0L, client.unread("other", "folder"))
        listeners[0].end()
        runCurrent()
        assertEquals(0L, client.unread("group", "folder"))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, listeners.size)
        listeners[0].text(update("9"))
        assertEquals(0L, client.unread("group", "folder"))
        listeners[1].text(update("3"))
        assertEquals(3L, client.unread("group", "folder"))
        client.close()
        listeners[1].text(update("8"))
        assertEquals(0L, client.unread("group", "folder"))
        assertEquals(2, closes)
    }
}
