package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.OkResponse
import uz.disastrouspumpkin.wdb.protocol.SemanticTreeRequest
import uz.disastrouspumpkin.wdb.protocol.SemanticTreeResponse
import uz.disastrouspumpkin.wdb.protocol.UiActionRequest
import uz.disastrouspumpkin.wdb.protocol.UiActionResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round-trips the devtools control ops (screenshot/semantic-tree/ui-action) through [FakeAgent]. */
class DevtoolsClientTest {

    @Test
    fun devtools_round_trip() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        // Screenshot comes back as a raw blob frame (add-binary-screenshot-transport); other ops via controlHandler.
        val fake = FakeAgent(
            screenshotPng = png,
            controlHandler = { req ->
                when (req) {
                    is SemanticTreeRequest -> SemanticTreeResponse(ok = true, tree = """{"id":1,"text":"x"}""")
                    is UiActionRequest -> UiActionResponse(ok = req.nodeId == 7)
                    else -> OkResponse
                }
            },
        )
        fake.use {
            val client = WdbClient(this)
            assertContentEquals(png, client.screenshot("m", fake.address))
            assertEquals("""{"id":1,"text":"x"}""", client.semanticTree("m", fake.address))
            assertTrue(client.uiAction("m", nodeId = 7, kind = uz.disastrouspumpkin.wdb.protocol.UiActionKind.CLICK, host = fake.address))
            assertFalse(client.uiAction("m", nodeId = 9, kind = uz.disastrouspumpkin.wdb.protocol.UiActionKind.CLICK, host = fake.address))
        }
    }

    @Test
    fun bring_to_front_round_trips() = runBlocking {
        // FakeAgent's default control handler returns OkResponse; expectOk must not throw.
        FakeAgent().use { fake -> WdbClient(this).bringToFront("m", fake.address) }
    }

    @Test
    fun screenshot_null_when_not_ok() = runBlocking {
        val fake = FakeAgent(screenshotPng = null) // header ok=false, no blob
        fake.use {
            val client = WdbClient(this)
            assertNull(client.screenshot("m", fake.address))
        }
    }

    @Test
    fun screenshot_handles_payload_over_1mib() = runBlocking {
        // The old 1 MiB frame cap + base64 broke fullscreen screenshots; a ~2 MiB raw PNG must round-trip now.
        val big = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val fake = FakeAgent(screenshotPng = big)
        fake.use {
            val client = WdbClient(this)
            assertContentEquals(big, client.screenshot("m", fake.address))
        }
    }
}
