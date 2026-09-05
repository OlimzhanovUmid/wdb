package uz.disastrouspumpkin.wdb.mcp

import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.Machine
import uz.disastrouspumpkin.wdb.client.WdbClient
import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.ControlRequest
import uz.disastrouspumpkin.wdb.protocol.ControlResponse
import uz.disastrouspumpkin.wdb.protocol.DesiredState
import uz.disastrouspumpkin.wdb.protocol.FrameCodec
import uz.disastrouspumpkin.wdb.protocol.Handshake
import uz.disastrouspumpkin.wdb.protocol.HandshakeAck
import uz.disastrouspumpkin.wdb.protocol.LogEvent
import uz.disastrouspumpkin.wdb.protocol.LogLine
import uz.disastrouspumpkin.wdb.protocol.LogStream
import uz.disastrouspumpkin.wdb.protocol.MachineStatus
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import uz.disastrouspumpkin.wdb.protocol.OkResponse
import uz.disastrouspumpkin.wdb.protocol.PushManifest
import uz.disastrouspumpkin.wdb.protocol.PushResult
import uz.disastrouspumpkin.wdb.protocol.ScreenshotRequest
import uz.disastrouspumpkin.wdb.protocol.ScreenshotResponse
import uz.disastrouspumpkin.wdb.protocol.StatusRequest
import uz.disastrouspumpkin.wdb.protocol.StatusResponse
import uz.disastrouspumpkin.wdb.protocol.StreamKind
import uz.disastrouspumpkin.wdb.protocol.UiActionKind
import uz.disastrouspumpkin.wdb.protocol.UiActionRequest
import uz.disastrouspumpkin.wdb.protocol.UiActionResponse
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smoke tests for the wdb-mcp tool bodies and helpers (add-wdb-mcp / add-wdb-mcp-v2): drives the
 * extracted `tool*` funcs, `MachineCache`, and `LogCollectors` against a minimal protocol-speaking
 * fake agent — no real MCP client or transport.
 */
class WdbMcpToolsTest {

    /** Minimal loopback agent: handshake + CONTROL, PUSH, and LOGS channels. */
    private class FakeAgent(
        private val controlHandler: (ControlRequest) -> ControlResponse = { OkResponse },
        private val logEvents: List<LogEvent> = emptyList(),
        private val screenshotPng: ByteArray? = null,
    ) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val address: AgentAddress get() = AgentAddress("127.0.0.1", server.localPort)

        init {
            Thread {
                try {
                    while (true) {
                        val s = server.accept()
                        Thread { handle(s) }.apply { isDaemon = true }.start()
                    }
                } catch (_: Throwable) { /* closed */ }
            }.apply { isDaemon = true }.start()
        }

        private fun handle(sock: Socket) {
            sock.use {
                val din = DataInputStream(BufferedInputStream(sock.getInputStream()))
                val dout = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
                val hs = MessageCodec.decode<Handshake>(FrameCodec.readFrame(din) ?: return)
                FrameCodec.writeFrame(dout, MessageCodec.encode(HandshakeAck(ok = true)))
                when (hs.kind) {
                    StreamKind.CONTROL -> {
                        val req = MessageCodec.decode(ControlRequest.serializer(), FrameCodec.readFrame(din) ?: return)
                        if (req is ScreenshotRequest) {
                            val png = screenshotPng
                            FrameCodec.writeFrame(dout, MessageCodec.encode(ControlResponse.serializer(), ScreenshotResponse(ok = png != null)))
                            if (png != null) FrameCodec.writeFrame(dout, png)
                        } else {
                            FrameCodec.writeFrame(dout, MessageCodec.encode(ControlResponse.serializer(), controlHandler(req)))
                        }
                    }
                    StreamKind.LOGS -> for (e in logEvents) {
                        FrameCodec.writeFrame(dout, MessageCodec.encode(LogEvent.serializer(), e))
                    }
                    StreamKind.PUSH -> {
                        val manifest = MessageCodec.decode<PushManifest>(FrameCodec.readFrame(din)!!)
                        while (true) {
                            val f = FrameCodec.readFrame(din) ?: break
                            if (f.isEmpty()) break
                        }
                        FrameCodec.writeFrame(
                            dout,
                            MessageCodec.encode(PushResult(ok = true, deployedSha = manifest.entries.first().sha256, restarted = manifest.restart)),
                        )
                    }
                    else -> Unit
                }
            }
        }

        override fun close() { runCatching { server.close() } }
    }

    private fun status(name: String) = MachineStatus(
        machineId = "id-$name",
        name = name,
        appState = AppState.RUNNING,
        desiredState = DesiredState.RUNNING,
        uptimeMillis = 1234,
        restartCount = 0,
        deployedSha = "abc123",
        mainClass = "uz.disastrouspumpkin.wdb.dummy.hot.HotAppKt",
        jdwpPort = 5005,
        hotMode = true,
        agentVersion = "0.2.8",
        runtimeVersion = "21.0.10",
    )

    // --- MachineCache ---

    @Test
    fun cache_hits_within_ttl_then_refreshes() = runBlocking {
        var clock = 0L
        var discovers = 0
        val cache = MachineCache(ttlMs = 1000, now = { clock }) {
            discovers++
            listOf(Machine(id = "i", name = "m", address = AgentAddress("10.0.0.1", 7420)))
        }
        assertEquals(1, cache.list().size)
        assertEquals(1, discovers)
        assertEquals(AgentAddress("10.0.0.1", 7420), cache.resolve("m")) // cache hit
        assertEquals(1, discovers)
        clock += 2000 // past TTL
        cache.resolve("m")
        assertEquals(2, discovers)
    }

    // --- status / deploy / ui_action / screenshot ---

    @Test
    fun status_round_trip() = runBlocking {
        FakeAgent(controlHandler = { req -> if (req is StatusRequest) StatusResponse(status("m")) else OkResponse }).use { fake ->
            val result = toolStatus(WdbClient(this), "m", fake.address)
            assertFalse(result.isError == true)
            val body = (result.content.single() as TextContent).text
            assertTrue("hot: true" in body)
            assertTrue("agent/rt:  0.2.8 / 21.0.10" in body)
        }
    }

    @Test
    fun status_unreachable_is_error() = runBlocking {
        val result = toolStatus(WdbClient(this), "gone", AgentAddress("127.0.0.1", 1)) // nothing listening
        assertEquals(true, result.isError)
    }

    @Test
    fun deploy_pushes_jar() = runBlocking {
        val jar = tempJarWithMain("uz.disastrouspumpkin.wdb.dummy.hot.HotAppKt")
        FakeAgent().use { fake ->
            val result = toolDeploy(WdbClient(this), "m", jar.toString(), host = fake.address)
            assertFalse(result.isError == true)
            assertTrue("deployed" in (result.content.single() as TextContent).text)
        }
    }

    @Test
    fun deploy_missing_jar_is_error() = runBlocking {
        val result = toolDeploy(WdbClient(this), "m", "C:/does/not/exist.jar", host = AgentAddress("127.0.0.1", 1))
        assertEquals(true, result.isError)
    }

    @Test
    fun ui_action_reports_applied() = runBlocking {
        FakeAgent(controlHandler = { req -> if (req is UiActionRequest) UiActionResponse(ok = req.nodeId == 7) else OkResponse }).use { fake ->
            val applied = toolUiAction(WdbClient(this), "m", nodeId = 7, kind = UiActionKind.CLICK, host = fake.address)
            assertFalse(applied.isError == true)
        }
    }

    @Test
    fun screenshot_round_trip() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        FakeAgent(screenshotPng = png).use { fake ->
            val result = toolScreenshot(WdbClient(this), "m", fake.address)
            assertEquals(Base64.getEncoder().encodeToString(png), (result.content.single() as ImageContent).data)
        }
    }

    // --- LogCollectors (streaming logs resource backing) ---

    @Test
    fun log_collector_buffers_lines() = runBlocking {
        val events = listOf(
            LogLine(LogStream.STDOUT, 0, "line-a"),
            LogLine(LogStream.STDOUT, 0, "line-b"),
        )
        FakeAgent(logEvents = events).use { fake ->
            val collectors = LogCollectors(this, WdbClient(this), coalesceMs = 30)
            collectors.read("s", "m", fake.address) {} // starts the collector
            var out = ""
            withTimeoutOrNull(3000) {
                while (isActive) {
                    out = collectors.read("s", "m", fake.address) {}
                    if ("line-a" in out && "line-b" in out) break
                    delay(30)
                }
            }
            collectors.cancelAll()
            assertTrue("line-a" in out, "buffer: $out")
            assertTrue("line-b" in out, "buffer: $out")
        }
    }

    private fun tempJarWithMain(mainClass: String): Path {
        val jar = Files.createTempFile("wdb-mcp-test", ".jar")
        val mf = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        }
        JarOutputStream(Files.newOutputStream(jar), mf).close()
        jar.toFile().deleteOnExit()
        return jar
    }
}
