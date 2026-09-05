package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.sendAgentUpdate
import uz.disastrouspumpkin.wdb.protocol.AgentUpdateManifest
import uz.disastrouspumpkin.wdb.protocol.ErrorCode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentUpdateTest {

    /** A tiny stand-in "app-image" zip whose exe content is tagged so we can tell versions apart. */
    private fun makeImageZip(tag: String): Path {
        val zip = Files.createTempFile("img", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("wdb-agent.exe")); z.write("EXE:$tag".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("app/wdb-agent-$AGENT_VERSION.jar")); z.write("JAR:$tag".toByteArray()); z.closeEntry()
        }
        return zip
    }

    private fun layout() = AgentInstallLayout(Files.createTempDirectory("wdb-base"))

    @Test
    fun `extract lands the app-image under the version dir`() {
        val l = layout()
        val dir = l.extract(makeImageZip("v2"), "v2")
        assertTrue(Files.exists(dir.resolve("wdb-agent.exe")))
        assertTrue(Files.exists(dir.resolve("app/wdb-agent-$AGENT_VERSION.jar")))
    }

    @Test
    fun `marker round-trips`() {
        val l = layout()
        l.writeMarker(PendingUpdate(previousVersion = "A", newVersion = "B", createdMillis = 123))
        assertEquals("A", l.readMarker()?.previousVersion)
        assertEquals("B", l.readMarker()?.newVersion)
        l.clearMarker()
        assertNull(l.readMarker())
    }

    @Test
    fun `switchTo sets current and tracks previous`() {
        val l = layout()
        l.extract(makeImageZip("A"), "A"); l.switchTo("A")
        assertEquals("A", l.currentVersion())
        assertEquals("EXE:A", Files.readString(l.versionDir("A").resolve("wdb-agent.exe")))

        l.extract(makeImageZip("B"), "B"); l.switchTo("B")
        assertEquals("B", l.currentVersion())
        assertEquals("A", l.previousVersion())
        assertEquals("EXE:B", Files.readString(l.versionDir("B").resolve("wdb-agent.exe")))
    }

    @Test
    fun `revertToPrevious swaps back`() {
        val l = layout()
        l.extract(makeImageZip("A"), "A"); l.switchTo("A")
        l.extract(makeImageZip("B"), "B"); l.switchTo("B")

        assertEquals("A", l.revertToPrevious())
        assertEquals("A", l.currentVersion())
    }

    @Test
    fun `writeLauncher emits a stub that reads current-version`() {
        val l = layout()
        l.switchTo("0.1.0")
        l.writeLauncher("wall-x", 5005)
        val cmd = Files.readString(l.launchCmd)
        assertTrue(cmd.contains("current-version"))
        assertTrue(cmd.contains("versions\\%WDBV%\\wdb-agent.exe"))
        assertTrue(cmd.contains("--name \"wall-x\""))
        assertTrue(cmd.contains("--jdwp-port 5005"))
    }

    @Test
    fun `SelfUpdater rejects a corrupt build and leaves current untouched`() {
        val l = layout()
        val su = SelfUpdater(l) { /* no restart */ }
        val zip = makeImageZip("v2")
        val bad = AgentUpdateManifest(version = "v2", sha256 = "deadbeef", size = Files.size(zip))
        val r = su.apply(bad, zip)
        assertFalse(r.ok)
        assertEquals(ErrorCode.INTEGRITY_FAILED, r.error?.code)
        assertNull(l.currentVersion())
    }

    @Test
    fun `SelfUpdater applies a valid build, extracts it and records the pending marker`() {
        val l = layout()
        val su = SelfUpdater(l) { /* no restart */ }
        val zip = makeImageZip("v2")
        val manifest = AgentUpdateManifest(version = "v2", sha256 = sha256(zip), size = Files.size(zip))
        val r = su.apply(manifest, zip)
        assertTrue(r.ok)
        // apply extracts, switches current-version (a plain file write), and marks pending.
        assertTrue(Files.exists(l.versionDir("v2").resolve("wdb-agent.exe")))
        assertEquals("v2", l.currentVersion())
        assertEquals("v2", l.readMarker()?.newVersion)
        assertTrue(Files.notExists(zip)) // temp consumed
    }

    @Test
    fun `apply writes an agent-update log trail`() {
        val l = layout()
        val su = SelfUpdater(l) { /* no restart */ }
        val zip = makeImageZip("v2")
        su.apply(AgentUpdateManifest(version = "v2", sha256 = sha256(zip), size = Files.size(zip)), zip)
        val log = Files.readString(l.agentDir.resolve("agent-update.log"))
        assertTrue(log.contains("apply start ver=v2"), log)
        assertTrue(log.contains("extracted versions/v2"), log)
        assertTrue(log.contains("switchTo v2"), log)
    }

    @Test
    fun `agent-update over the wire extracts the new version and records the marker`() {
        val dataDir = Files.createTempDirectory("wdb-au-data")
        val id = loadOrCreateMachineId(AgentPaths(dataDir))
        val config = AgentConfig(machineName = "m", machineId = id, dataDir = dataDir, tcpPort = 0, udpPort = 0)
        val hub = LogHub()
        val store = DeploymentStore(config.paths)
        val state = AgentState(config.paths)
        val supervisor = Supervisor(config, store, state, hub)
        val layout = AgentInstallLayout(Files.createTempDirectory("wdb-au-base"))
        val server = AgentServer(config, store, supervisor, hub, SelfUpdater(layout) { /* no restart in test */ })
        server.start()
        try {
            val addr = AgentAddress("127.0.0.1", server.port())
            val zip = makeImageZip("v9")
            val result = runBlocking { sendAgentUpdate(addr, zip, "v9") }
            assertTrue(result.ok, "agent-update should succeed")
            assertTrue(Files.exists(layout.versionDir("v9").resolve("wdb-agent.exe")))
            assertEquals("v9", layout.currentVersion()) // switched via current-version file

        } finally {
            server.close()
            supervisor.shutdown()
        }
    }
}
