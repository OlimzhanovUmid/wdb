package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.DesiredState
import uz.disastrouspumpkin.wdb.protocol.LogLine
import uz.disastrouspumpkin.wdb.protocol.LogStream
import uz.disastrouspumpkin.wdb.protocol.MachineStatus
import uz.disastrouspumpkin.wdb.protocol.OkResponse
import uz.disastrouspumpkin.wdb.protocol.RunBoundary
import uz.disastrouspumpkin.wdb.protocol.RunRequest
import uz.disastrouspumpkin.wdb.protocol.StatusRequest
import uz.disastrouspumpkin.wdb.protocol.StatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientIntegrationTest {

    private val sampleStatus = MachineStatus(
        machineId = "id1", name = "wall-03", appState = AppState.RUNNING,
        desiredState = DesiredState.RUNNING, agentVersion = "0.1.0", runtimeVersion = "21",
    )

    private fun tempCache() = LastSeenCache(Files.createTempFile("wdb-cache", ".json"))

    @Test
    fun `control request-response over a real connection`() = runBlocking {
        FakeAgent(controlHandler = { if (it is StatusRequest) StatusResponse(sampleStatus) else OkResponse }).use { agent ->
            val resp = sendControl(agent.address, StatusRequest)
            val status = assertIs<StatusResponse>(resp)
            assertEquals("wall-03", status.status.name)
            assertEquals(OkResponse, sendControl(agent.address, RunRequest))
        }
    }

    @Test
    fun `cancellation closes the socket and unblocks a pending read`() = runBlocking {
        FakeAgent(hangControl = true).use { agent ->
            val job = launch(Dispatchers.Default) { runCatching { sendControl(agent.address, StatusRequest) } }
            delay(300) // let it connect and block on the response read
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `tunnel relays bytes both ways`() = runBlocking {
        FakeAgent().use { agent ->
            coroutineScope {
                val tunnel = openTunnel(this, agent.address, remoteLoopbackPort = 9999)
                Socket("127.0.0.1", tunnel.localPort).use { s ->
                    s.getOutputStream().write("ping".encodeToByteArray())
                    s.getOutputStream().flush()
                    val buf = ByteArray(4)
                    DataInputStream(s.getInputStream()).readFully(buf)
                    assertEquals("ping", buf.decodeToString())
                }
                tunnel.close()
            }
        }
    }

    @Test
    fun `logs stream is delivered in order`() = runBlocking {
        val events = listOf(
            RunBoundary(runId = 1, timestampMillis = 0),
            LogLine(LogStream.STDOUT, 1, "hello"),
            LogLine(LogStream.STDERR, 2, "boom"),
        )
        FakeAgent(logEvents = events).use { agent ->
            val got = streamLogs(agent.address).toList()
            assertEquals(events, got)
        }
    }

    @Test
    fun `push streams the jar and returns the deployed sha`() = runBlocking {
        val jar = Files.createTempFile("wdb-app", ".jar")
        Files.write(jar, ByteArray(200_000) { (it % 251).toByte() })
        FakeAgent().use { agent ->
            val result = pushJar(agent.address, jar, mainClass = "com.example.MainKt", restart = false)
            assertTrue(result.ok)
            assertEquals(sha256(jar), result.deployedSha)
            assertEquals(false, result.restarted)
        }
    }

    @Test
    fun `facade resolves via host override and issues control commands`() = runBlocking {
        FakeAgent(controlHandler = { if (it is StatusRequest) StatusResponse(sampleStatus) else OkResponse }).use { agent ->
            val client = WdbClient(this, cache = tempCache())
            val status = client.status("wall-03", host = agent.address)
            assertEquals("wall-03", status.name)
            client.run("wall-03", host = agent.address) // must not throw
        }
    }
}
