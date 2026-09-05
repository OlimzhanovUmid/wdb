package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.LastSeenCache
import uz.disastrouspumpkin.wdb.client.WdbClient
import uz.disastrouspumpkin.wdb.protocol.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdwpPortTest {

    private val dummyJar = Path.of(System.getProperty("wdb.dummyJar"))
    private val mainClass = "uz.disastrouspumpkin.wdb.dummy.MainKt"

    // These two tests launch the real dummy app and assert exact JDWP-port binding. Picking a
    // "free" port (ServerSocket(0) then close) is a TOCTOU race: on a busy CI runner the port can
    // be taken before the app binds `-agentlib:jdwp`, forcing an ephemeral fallback and a flaky
    // failure. Gate them behind an opt-in flag so `./gradlew build` in CI stays deterministic;
    // run locally with `-Dwdb.integrationTests=true`. (The persist test below is a pure unit test.)
    private val runIntegration = System.getProperty("wdb.integrationTests") == "true"

    private fun newRuntime(jdwpPort: Int): AgentRuntime {
        val dir = Files.createTempDirectory("wdb-jdwp")
        val id = loadOrCreateMachineId(AgentPaths(dir))
        return AgentRuntime(AgentConfig(machineName = "m", machineId = id, dataDir = dir, tcpPort = 0, udpPort = 0, jdwpPort = jdwpPort))
    }

    private fun tempCache() = LastSeenCache(Files.createTempFile("cache", ".json"))
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun eventually(timeoutMs: Long = 15_000, block: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (block()) return
            delay(150)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `jdwp port persists and defaults to null when unset`() {
        val paths = AgentPaths(Files.createTempDirectory("wdb-jdwp-persist"))
        assertNull(readPersistedJdwpPort(paths))
        persistJdwpPort(paths, 6006)
        assertEquals(6006, readPersistedJdwpPort(paths))
    }

    @Test
    fun `fixed jdwp port is used and stays the same across a restart`() = runBlocking {
        assumeTrue(runIntegration, "integration test (launches app + binds JDWP); enable with -Dwdb.integrationTests=true")
        val port = freePort()
        newRuntime(port).use { rt ->
            rt.start()
            val addr = AgentAddress("127.0.0.1", rt.port())
            val client = WdbClient(this, cache = tempCache())
            client.push("m", dummyJar, mainClass, host = addr)
            client.run("m", host = addr)
            eventually {
                val s = client.status("m", host = addr)
                s.appState == AppState.RUNNING && s.jdwpPort == port && !s.jdwpPortIsFallback
            }
            client.restart("m", host = addr)
            eventually {
                val s = client.status("m", host = addr)
                s.appState == AppState.RUNNING && s.jdwpPort == port && !s.jdwpPortIsFallback
            }
        }
    }

    @Test
    fun `falls back to an ephemeral port when the fixed port is busy, and recovers when it frees`() = runBlocking {
        assumeTrue(runIntegration, "integration test (launches app + binds JDWP); enable with -Dwdb.integrationTests=true")
        val port = freePort()
        val blocker = ServerSocket(port, 1, InetAddress.getLoopbackAddress()) // occupy the fixed port
        newRuntime(port).use { rt ->
            rt.start()
            val addr = AgentAddress("127.0.0.1", rt.port())
            val client = WdbClient(this, cache = tempCache())
            client.push("m", dummyJar, mainClass, host = addr)
            client.run("m", host = addr)
            eventually {
                val s = client.status("m", host = addr)
                s.appState == AppState.RUNNING && s.jdwpPortIsFallback && s.jdwpPort != port
            }

            blocker.close() // free the fixed port
            client.restart("m", host = addr)
            eventually {
                val s = client.status("m", host = addr)
                s.appState == AppState.RUNNING && !s.jdwpPortIsFallback && s.jdwpPort == port
            }
        }
    }
}
