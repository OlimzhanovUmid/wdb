package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.ReloadChangeType
import uz.disastrouspumpkin.wdb.protocol.ReloadOutcome
import uz.disastrouspumpkin.wdb.protocol.ReloadResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReloadTest {

    private fun writeClass(dir: Path, rel: String, body: String) {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, body.toByteArray())
    }

    @Test
    fun `diff reports added, modified, removed and skips unchanged`() {
        val old = mapOf("a/A.class" to "1", "b/B.class" to "2", "c/C.class" to "3")
        val new = mapOf("a/A.class" to "1", "b/B.class" to "changed", "d/D.class" to "9")
        val byType = ClassDiff.diff(old, new).associate { it.relPath to it.changeType }

        assertNull(byType["a/A.class"]) // unchanged -> not in the delta
        assertEquals(ReloadChangeType.MODIFIED, byType["b/B.class"])
        assertEquals(ReloadChangeType.REMOVED, byType["c/C.class"])
        assertEquals(ReloadChangeType.ADDED, byType["d/D.class"])
        assertEquals(3, byType.size)
    }

    @Test
    fun `snapshot hashes every class and buildPayload carries only changed bytes`() {
        val dir = Files.createTempDirectory("classes")
        writeClass(dir, "com/example/App.class", "v1")
        writeClass(dir, "com/example/Util.class", "util")

        val (firstPayload, snap1) = ClassDiff.buildPayload(dir, emptyMap())
        // First push establishes the baseline: both classes ADDED, both bytes present.
        assertEquals(2, firstPayload.batch.entries.size)
        assertTrue(firstPayload.batch.entries.all { it.changeType == ReloadChangeType.ADDED })
        assertEquals(setOf("com/example/App.class", "com/example/Util.class"), firstPayload.bytesByPath.keys)

        // Change one class only; the next payload carries exactly that one.
        writeClass(dir, "com/example/App.class", "v2-longer")
        val (delta, _) = ClassDiff.buildPayload(dir, snap1)
        assertEquals(1, delta.batch.entries.size)
        val e = delta.batch.entries.single()
        assertEquals("com/example/App.class", e.relPath)
        assertEquals(ReloadChangeType.MODIFIED, e.changeType)
        assertEquals("v2-longer".toByteArray().size.toLong(), e.size)
        assertEquals(setOf("com/example/App.class"), delta.bytesByPath.keys)
    }

    @Test
    fun `sendReload streams the batch to the agent and returns its result`() {
        val agent = FakeAgent(reloadHandler = { ReloadResult(ReloadOutcome.APPLIED) })
        agent.use {
            val dir = Files.createTempDirectory("classes")
            writeClass(dir, "com/example/App.class", "hello")
            writeClass(dir, "com/example/New.class", "n")
            val (payload, _) = ClassDiff.buildPayload(dir, emptyMap())

            val result = runBlocking { sendReload(agent.address, payload) }
            assertEquals(ReloadOutcome.APPLIED, result.outcome)

            val received = agent.reloadBatches.single()
            assertEquals(payload.batch, received)
        }
    }

    @Test
    fun `reloadOrRedeploy falls back to redeploy only on FAILED, not on REJECTED or APPLIED`() {
        val dir = Files.createTempDirectory("classes")
        writeClass(dir, "com/example/App.class", "x")
        val (payload, _) = ClassDiff.buildPayload(dir, emptyMap())

        runBlocking {
            // APPLIED -> no redeploy
            FakeAgent(reloadHandler = { ReloadResult(ReloadOutcome.APPLIED) }).use { a ->
                var redeployed = false
                val client = WdbClient(this)
                val report = client.reloadOrRedeploy("m", payload, host = a.address, redeploy = { redeployed = true; error("unused") })
                assertTrue(report is ReloadReport.Applied)
                assertFalse(redeployed)
            }
            // REJECTED (not hot) -> no redeploy
            FakeAgent(reloadHandler = { ReloadResult(ReloadOutcome.REJECTED, "not hot") }).use { a ->
                var redeployed = false
                val client = WdbClient(this)
                val report = client.reloadOrRedeploy("m", payload, host = a.address, redeploy = { redeployed = true; error("unused") })
                assertTrue(report is ReloadReport.Rejected)
                assertFalse(redeployed)
            }
            // FAILED -> redeploy fallback fires
            FakeAgent(reloadHandler = { ReloadResult(ReloadOutcome.FAILED, "unsupported") }).use { a ->
                var redeployed = false
                val client = WdbClient(this)
                val report = client.reloadOrRedeploy("m", payload, host = a.address, redeploy = {
                    redeployed = true
                    uz.disastrouspumpkin.wdb.protocol.PushResult(ok = true, deployedSha = "new", restarted = true)
                })
                assertTrue(report is ReloadReport.Redeployed)
                assertTrue(redeployed)
            }
        }
    }

    @Test
    fun `sendReload surfaces a FAILED result for the redeploy fallback`() {
        val agent = FakeAgent(reloadHandler = { ReloadResult(ReloadOutcome.FAILED, "unsupported change") })
        agent.use {
            val dir = Files.createTempDirectory("classes")
            writeClass(dir, "com/example/App.class", "x")
            val (payload, _) = ClassDiff.buildPayload(dir, emptyMap())
            val result = runBlocking { sendReload(agent.address, payload) }
            assertEquals(ReloadOutcome.FAILED, result.outcome)
            assertEquals("unsupported change", result.reason)
        }
    }
}
