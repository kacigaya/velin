package app.velin.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class CounterEventsTest {
    private fun update(count: String = "7", folder: String = "folder") =
        """unreadCounterUpdate;{"1494:mailGroup":"group","1495:counterValues":[{"1490":"$folder","1491":"$count"}]}"""
    @Test fun decodeReleaseFieldsAndRejectInvalidCounts() {
        assertEquals(CounterUpdate("group", mapOf("folder" to 7L)), parseCounterEvent(update()))
        assertNull(parseCounterEvent("entityUpdate;ignored"))
        listOf("-1", "2147483648", "1.5", "1e3", "NaN").forEach { count ->
            assertTrue(runCatching { parseCounterEvent(update(count)) }.isFailure)
        }
        assertTrue(runCatching { parseCounterEvent("x".repeat(MAX_EVENT_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { parseCounterEvent("unreadCounterUpdate;" + "[".repeat(100)) }.isFailure)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun reconnectAndCloseDiscardLateUpdates() = runTest {
        val listeners = mutableListOf<EventListener>()
        var closes = 0
        val client = CounterEvents(backgroundScope, { listener -> listeners += listener; AutoCloseable { closes++ } })
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
