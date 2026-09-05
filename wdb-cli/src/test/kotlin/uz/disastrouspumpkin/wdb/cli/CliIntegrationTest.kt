package uz.disastrouspumpkin.wdb.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import uz.disastrouspumpkin.wdb.agent.AgentConfig
import uz.disastrouspumpkin.wdb.agent.AgentPaths
import uz.disastrouspumpkin.wdb.agent.AgentRuntime
import uz.disastrouspumpkin.wdb.agent.loadOrCreateMachineId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class CliIntegrationTest {

    private val dummyJar = Path.of(System.getProperty("wdb.dummyJar"))

    private fun root() = Wdb().subcommands(
        Devices(), Status(), Run(), Stop(), Restart(), Rollback(), Push(), Logs(), Debug(),
    )

    @Test
    fun `push, run and status against a real agent`() {
        val dir = Files.createTempDirectory("wdb-cli-e2e")
        val id = loadOrCreateMachineId(AgentPaths(dir))
        val runtime = AgentRuntime(AgentConfig(machineName = "m", machineId = id, dataDir = dir, tcpPort = 0, udpPort = 0))
        runtime.start()
        try {
            val hostArg = "127.0.0.1:${runtime.port()}"

            val push = root().test(arrayOf("push", dummyJar.toString(), "m", "--host", hostArg))
            assertTrue(push.stdout.contains("ok"), "push output: ${push.stdout}")

            val run = root().test(arrayOf("run", "m", "--host", hostArg))
            assertTrue(run.stdout.contains("running"), "run output: ${run.stdout}")

            var status = ""
            for (i in 0 until 60) {
                status = root().test(arrayOf("status", "m", "--host", hostArg)).stdout
                if (status.contains("RUNNING")) break
                Thread.sleep(200)
            }
            assertTrue(status.contains("RUNNING"), "status never reported RUNNING: $status")
        } finally {
            runtime.close()
        }
    }
}
