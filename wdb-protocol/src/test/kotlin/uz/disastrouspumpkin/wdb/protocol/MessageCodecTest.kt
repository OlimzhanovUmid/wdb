package uz.disastrouspumpkin.wdb.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageCodecTest {

    @Test
    fun `control requests round-trip through the sealed serializer with discriminator`() {
        val requests: List<ControlRequest> =
            listOf(RunRequest, StopRequest, RestartRequest, RollbackRequest, StatusRequest, DebugSuspendRequest, HotRunRequest)
        for (r in requests) {
            val bytes = MessageCodec.encode(ControlRequest.serializer(), r)
            val back = MessageCodec.decode(ControlRequest.serializer(), bytes)
            assertEquals(r, back)
        }
    }

    @Test
    fun `run request serializes with a type discriminator`() {
        val bytes = MessageCodec.encode(ControlRequest.serializer(), RunRequest)
        assertTrue(bytes.decodeToString().contains("\"type\":\"run\""))
    }

    @Test
    fun `status response round-trips with full payload`() {
        val status = MachineStatus(
            machineId = "id-1",
            name = "wall-03",
            appState = AppState.RUNNING,
            desiredState = DesiredState.RUNNING,
            uptimeMillis = 1234,
            restartCount = 2,
            lastExitCode = null,
            deployedSha = "abc",
            previousSha = "def",
            mainClass = "com.example.MainKt",
            jdwpPort = 5005,
            jdwpPortIsFallback = true,
            agentVersion = "0.1.0",
            runtimeVersion = "21.0.9",
        )
        val resp: ControlResponse = StatusResponse(status)
        val back = MessageCodec.decode(ControlResponse.serializer(), MessageCodec.encode(ControlResponse.serializer(), resp))
        val s = assertIs<StatusResponse>(back)
        assertEquals(status, s.status)
    }

    @Test
    fun `status without the fallback flag decodes to false (backward compatible)`() {
        val json = """{"type":"status","status":{"machineId":"i","name":"n","appState":"RUNNING",""" +
            """"desiredState":"RUNNING","jdwpPort":5005,"agentVersion":"0.1.0","runtimeVersion":"21"}}"""
        val back = MessageCodec.decode(ControlResponse.serializer(), json.encodeToByteArray())
        val s = assertIs<StatusResponse>(back)
        assertEquals(false, s.status.jdwpPortIsFallback)
    }

    @Test
    fun `error response round-trips`() {
        val resp: ControlResponse = ErrorResponse(ProtocolError(ErrorCode.NOT_DEPLOYED, "nothing deployed"))
        val back = MessageCodec.decode(ControlResponse.serializer(), MessageCodec.encode(ControlResponse.serializer(), resp))
        val e = assertIs<ErrorResponse>(back)
        assertEquals(ErrorCode.NOT_DEPLOYED, e.error.code)
    }

    @Test
    fun `push manifest and result round-trip`() {
        val manifest = PushManifest(
            entries = listOf(BlobEntry("app.jar", "sha", 80_000_000)),
            mainClass = "com.example.MainKt",
            jvmArgs = listOf("-Xmx1g"),
            programArgs = listOf("--kiosk"),
            restart = false,
        )
        assertEquals(manifest, MessageCodec.decode<PushManifest>(MessageCodec.encode(manifest)))

        val result = PushResult(ok = true, deployedSha = "sha", restarted = true)
        assertEquals(result, MessageCodec.decode<PushResult>(MessageCodec.encode(result)))
    }

    @Test
    fun `agent-update manifest round-trips`() {
        val m = AgentUpdateManifest(version = "0.2.0", sha256 = "abc", size = 123_456_789)
        assertEquals(m, MessageCodec.decode<AgentUpdateManifest>(MessageCodec.encode(m)))
    }

    @Test
    fun `reload batch and result round-trip, including a REMOVED entry with no bytes`() {
        val batch = ReloadBatch(
            entries = listOf(
                ReloadEntry("com/example/App.class", ReloadChangeType.MODIFIED, sha256 = "aa", size = 512),
                ReloadEntry("com/example/New.class", ReloadChangeType.ADDED, sha256 = "bb", size = 128),
                ReloadEntry("com/example/Gone.class", ReloadChangeType.REMOVED),
            ),
            batchSha256 = "batch-sha",
        )
        val back = MessageCodec.decode<ReloadBatch>(MessageCodec.encode(batch))
        assertEquals(batch, back)
        val removed = back.entries.single { it.changeType == ReloadChangeType.REMOVED }
        assertEquals(0, removed.size)
        assertEquals("", removed.sha256)

        for (r in listOf(ReloadResult(ReloadOutcome.APPLIED), ReloadResult(ReloadOutcome.FAILED, "boom"))) {
            assertEquals(r, MessageCodec.decode<ReloadResult>(MessageCodec.encode(r)))
        }
    }

    @Test
    fun `log events round-trip through the sealed serializer`() {
        val events: List<LogEvent> = listOf(
            LogLine(LogStream.STDOUT, 1, "hello"),
            LogLine(LogStream.STDERR, 2, "boom"),
            RunBoundary(runId = 7, timestampMillis = 3),
            DroppedMarker(count = 42, timestampMillis = 4),
        )
        for (e in events) {
            val back = MessageCodec.decode(LogEvent.serializer(), MessageCodec.encode(LogEvent.serializer(), e))
            assertEquals(e, back)
        }
    }

    @Test
    fun `discovery query and answer round-trip`() {
        val q = DiscoveryQuery(nonce = "n1")
        assertEquals(q, MessageCodec.decode<DiscoveryQuery>(MessageCodec.encode(q)))

        val a = DiscoveryAnswer(
            machineId = "id", name = "wall-01", host = "192.168.1.5", port = 7420,
            appState = AppState.STOPPED, desiredState = DesiredState.STOPPED, nonce = "n1",
        )
        assertEquals(a, MessageCodec.decode<DiscoveryAnswer>(MessageCodec.encode(a)))
    }

    @Test
    fun `unknown keys are ignored for forward compatibility`() {
        val json = """{"type":"status","status":{"machineId":"i","name":"n","appState":"RUNNING",""" +
            """"desiredState":"RUNNING","agentVersion":"0.1.0","runtimeVersion":"21","futureField":123}}"""
        val back = MessageCodec.decode(ControlResponse.serializer(), json.encodeToByteArray())
        assertIs<StatusResponse>(back)
    }
}
