package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.AgentErrorException
import uz.disastrouspumpkin.wdb.client.LastSeenCache
import uz.disastrouspumpkin.wdb.client.WdbClient
import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.LogLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the REAL agent (server + supervisor + deploy + logs + tunnel) against
 * the REAL wdb-client, launching the real dummy JVM. Covers tasks 4.2, 5.1/5.3,
 * 6.1/6.2/6.4, 7.1 and the tunnel end-to-end.
 */
class AgentEndToEndTest {

    private val dummyJar = Path.of(System.getProperty("wdb.dummyJar"))
    private val mainClass = "uz.disastrouspumpkin.wdb.dummy.MainKt"

    private fun newRuntime(dir: Path = Files.createTempDirectory("wdb-e2e")): AgentRuntime {
        val id = loadOrCreateMachineId(AgentPaths(dir))
        return AgentRuntime(AgentConfig(machineName = "m", machineId = id, dataDir = dir, tcpPort = 0, udpPort = 0))
    }

    private fun tempCache() = LastSeenCache(Files.createTempFile("cache", ".json"))

    private suspend fun eventually(timeoutMs: Long = 12_000, block: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (block()) return
            delay(150)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `push, run, stream logs, stop`() = runBlocking {
        newRuntime().use { rt ->
            rt.start()
            val addr = AgentAddress("127.0.0.1", rt.port())
            val client = WdbClient(this, cache = tempCache())

            val push = client.push("m", dummyJar, mainClass, host = addr)
            assertTrue(push.ok, "push should succeed")

            client.run("m", host = addr)
            eventually { client.status("m", host = addr).appState == AppState.RUNNING }

            val tick = withTimeoutOrNull(8_000) {
                client.logs("m", host = addr).first { it is LogLine && it.text.contains("tick") }
            }
            assertNotNull(tick, "should stream a tick log line from the running app")

            client.stop("m", host = addr)
            eventually { client.status("m", host = addr).appState == AppState.STOPPED }
        }
    }

    @Test
    fun `crash then auto-restart recovers to running`() = runBlocking {
        val marker = Files.createTempFile("crash-marker", ".txt")
        Files.delete(marker) // absent -> first run crashes, second run stays up
        newRuntime().use { rt ->
            rt.start()
            val addr = AgentAddress("127.0.0.1", rt.port())
            val client = WdbClient(this, cache = tempCache())

            client.push("m", dummyJar, mainClass, programArgs = listOf("--crash-once=$marker"), host = addr)
            client.run("m", host = addr)

            eventually(20_000) {
                val st = client.status("m", host = addr)
                st.restartCount >= 1 && st.appState == AppState.RUNNING
            }
        }
    }

    @Test
    fun `stage-only push does not restart a running app`() = runBlocking {
        newRuntime().use { rt ->
            rt.start()
            val addr = AgentAddress("127.0.0.1", rt.port())
            val client = WdbClient(this, cache = tempCache())

            client.push("m", dummyJar, mainClass, host = addr)
            client.run("m", host = addr)
            eventually { client.status("m", host = addr).appState == AppState.RUNNING }

            val staged = client.push("m", dummyJar, mainClass, restart = false, host = addr)
            assertEquals(false, staged.restarted)
            assertEquals(AppState.RUNNING, client.status("m", host = addr).appState)
        }
    }

    @Test
    fun `rollback with no previous deployment errors`() = runBlocking {
        newRuntime().use { rt ->
            rt.start()
            val addr = AgentAddress("127.0.0.1", rt.port())
            val client = WdbClient(this, cache = tempCache())

            client.push("m", dummyJar, mainClass, host = addr)
            val ex = runCatching { client.rollback("m", host = addr) }.exceptionOrNull()
            assertIs<AgentErrorException>(ex)
        }
    }

    @Test
    fun `jdwp port is exposed and reachable through a tunnel`() = runBlocking {
        newRuntime().use { rt ->
            rt.start()
            val addr = AgentAddress("127.0.0.1", rt.port())
            val client = WdbClient(this, cache = tempCache())

            client.push("m", dummyJar, mainClass, host = addr)
            client.run("m", host = addr)

            var jdwp = 0
            eventually {
                val st = client.status("m", host = addr)
                jdwp = st.jdwpPort ?: 0
                st.appState == AppState.RUNNING && jdwp != 0
            }

            val tunnel = client.openTunnel("m", remoteLoopbackPort = jdwp, host = addr)
            try {
                // A JDWP server replies to the client's handshake with the same 14 bytes.
                eventually {
                    try {
                        Socket("127.0.0.1", tunnel.localPort).use { s ->
                            s.getOutputStream().write("JDWP-Handshake".encodeToByteArray())
                            s.getOutputStream().flush()
                            val buf = ByteArray(14)
                            DataInputStream(s.getInputStream()).readFully(buf)
                            buf.decodeToString() == "JDWP-Handshake"
                        }
                    } catch (e: Throwable) {
                        false
                    }
                }
            } finally {
                tunnel.close()
            }
        }
    }

    @Test
    fun `desired running state survives an agent restart`() = runBlocking {
        val dir = Files.createTempDirectory("wdb-persist")
        val cache = tempCache()

        val rt1 = newRuntime(dir)
        rt1.start()
        val client = WdbClient(this, cache = cache)
        val addr1 = AgentAddress("127.0.0.1", rt1.port())
        client.push("m", dummyJar, mainClass, host = addr1)
        client.run("m", host = addr1)
        eventually { client.status("m", host = addr1).appState == AppState.RUNNING }
        rt1.close() // shutdown kills the app but leaves desired=RUNNING persisted

        newRuntime(dir).use { rt2 ->
            rt2.start() // applyDesiredOnStartup should relaunch
            val addr2 = AgentAddress("127.0.0.1", rt2.port())
            eventually(20_000) { client.status("m", host = addr2).appState == AppState.RUNNING }
        }
    }
}
