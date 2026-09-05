package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.DroppedMarker
import uz.disastrouspumpkin.wdb.protocol.LogLine
import uz.disastrouspumpkin.wdb.protocol.RunBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LogHubTest {

    @Test
    fun `subscribe delivers history for current and previous run with a boundary`() {
        val hub = LogHub()
        hub.beginRun()
        hub.stdout("a1")
        hub.stdout("a2")
        hub.beginRun() // rotate: run 1 -> previous
        hub.stdout("b1")

        val (_, history) = hub.subscribe()
        val texts = history.filterIsInstance<LogLine>().map { it.text }
        assertEquals(listOf("a1", "a2", "b1"), texts)
        assertEquals(2, history.filterIsInstance<RunBoundary>().size) // both run boundaries retained
    }

    @Test
    fun `unobserved crash is diagnosable after subscribing`() {
        val hub = LogHub()
        hub.beginRun()
        hub.stdout("before-crash")
        hub.beginRun() // crash + relaunch happened with nobody watching
        hub.stdout("after-restart")

        val (_, history) = hub.subscribe()
        val texts = history.filterIsInstance<LogLine>().map { it.text }
        assertTrue("before-crash" in texts) // the crashed run's tail survived
        assertTrue("after-restart" in texts)
    }

    @Test
    fun `slow subscriber drops oldest, reports the gap, and still delivers survivors`() {
        val hub = LogHub(subscriberQueueCap = 4)
        hub.beginRun()
        val (sub, _) = hub.subscribe()
        repeat(20) { hub.stdout("line-$it") } // 20 events into a 4-slot queue, nobody draining

        // Drain everything the subscriber has: the 4 surviving lines plus one dropped marker.
        val events = buildList {
            while (true) add(sub.poll(50) ?: break)
        }
        val marker = events.filterIsInstance<DroppedMarker>().single()
        assertTrue(marker.count >= 16) // at least 16 dropped
        val lines = events.filterIsInstance<LogLine>()
        assertTrue(lines.isNotEmpty() && lines.size <= 4) // survivors delivered, not starved
    }
}
