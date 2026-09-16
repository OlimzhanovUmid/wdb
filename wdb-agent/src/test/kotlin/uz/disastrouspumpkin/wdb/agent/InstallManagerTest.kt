package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit-tests the InstallManager wiring with system commands injected (no schtasks/netsh/taskkill). */
class InstallManagerTest {

    @Test
    fun `finalize writes the layout and launcher without copying`() {
        val base = Files.createTempDirectory("wdb-finalize")
        val layout = AgentInstallLayout(base)
        // Pretend the installer already placed the app-image at versions/<ver>/.
        val versionDir = layout.versionDir(AGENT_VERSION)
        Files.createDirectories(versionDir)
        Files.writeString(versionDir.resolve("wdb-agent.exe"), "stub-exe")

        val calls = mutableListOf<List<String>>()
        val mgr = InstallManager(launcherPath = "irrelevant", user = "DOMAIN\\kiosk", exec = { calls += it; "[0] ok" })
        mgr.finalize(base, machineName = "wall-09")

        assertEquals(AGENT_VERSION, layout.currentVersion())          // pointer set
        assertTrue(Files.exists(layout.launchCmd))                    // launcher written
        assertTrue("wall-09" in Files.readString(layout.launchCmd))   // name baked in
        assertEquals("stub-exe", Files.readString(versionDir.resolve("wdb-agent.exe"))) // NOT overwritten (no copy)
        assertTrue(calls.any { "schtasks" in it && "/create" in it }) // task registered
        assertTrue(calls.any { "netsh" in it })                       // firewall added
    }

    @Test
    fun `uninstall stops the running agent then removes task and firewall`() {
        val calls = mutableListOf<List<String>>()
        InstallManager(exec = { calls += it; "[0] ok" }).uninstall()

        // Order: end the task instance + kill other agent processes BEFORE deleting the task.
        val endIdx = calls.indexOfFirst { "/end" in it }
        val killIdx = calls.indexOfFirst { "taskkill" in it }
        val deleteIdx = calls.indexOfFirst { "/delete" in it }
        assertTrue(endIdx >= 0 && killIdx >= 0 && deleteIdx >= 0)
        assertTrue(endIdx < deleteIdx && killIdx < deleteIdx)
        assertTrue(calls.any { "delete" in it && "rule" in it })      // firewall removed
        // taskkill must exclude the uninstaller's own pid.
        assertTrue(calls.any { c -> "taskkill" in c && c.any { it.startsWith("PID ne ") } })
    }
}
