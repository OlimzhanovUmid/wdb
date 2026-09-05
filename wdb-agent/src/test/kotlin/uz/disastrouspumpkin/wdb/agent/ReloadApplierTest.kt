package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.client.ClassDiff
import uz.disastrouspumpkin.wdb.protocol.ReloadBatch
import uz.disastrouspumpkin.wdb.protocol.ReloadChangeType
import uz.disastrouspumpkin.wdb.protocol.ReloadEntry
import uz.disastrouspumpkin.wdb.protocol.ReloadOutcome
import uz.disastrouspumpkin.wdb.protocol.ReloadResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadApplierTest {

    private class FakeCoordinator(val outcome: ReloadOutcome = ReloadOutcome.APPLIED) : HotReloadCoordinator {
        var applied: Map<String, ReloadChangeType>? = null
        override fun beginHotRun(): HotLaunch = error("not used")
        override fun applyReload(changed: Map<String, ReloadChangeType>): ReloadResult {
            applied = changed
            return ReloadResult(outcome)
        }
        override fun endHotRun() {}
        override fun screenshot(): ByteArray? = null
        override fun semanticTree(): String? = null
        override fun uiAction(nodeId: Int, action: uz.disastrouspumpkin.wdb.protocol.UiActionRequest): Boolean = false
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Build a well-formed batch (matching shas + batch sha) plus its bytes, for one MODIFIED class. */
    private fun goodBatch(rel: String, bytes: ByteArray): Pair<ReloadBatch, Map<String, ByteArray>> {
        val entry = ReloadEntry(rel, ReloadChangeType.MODIFIED, sha(bytes), bytes.size.toLong())
        val batch = ReloadBatch(listOf(entry), ClassDiff.batchSha256(listOf(entry)))
        return batch to mapOf(rel to bytes)
    }

    @Test
    fun `rejects when no hot run is active and leaves the coordinator untouched`() {
        val coord = FakeCoordinator()
        val applier = ReloadApplier(isHotRunning = { false }, hotDir = { Files.createTempDirectory("hot") }, coordinator = coord)
        val (batch, bytes) = goodBatch("com/example/App.class", "v1".toByteArray())

        val r = applier.apply(batch, bytes)
        assertEquals(ReloadOutcome.REJECTED, r.outcome)
        assertTrue(coord.applied == null, "coordinator must not be called")
    }

    @Test
    fun `rejects a corrupt batch and leaves the app untouched`() {
        val hotDir = Files.createTempDirectory("hot")
        val coord = FakeCoordinator()
        val applier = ReloadApplier(isHotRunning = { true }, hotDir = { hotDir }, coordinator = coord)

        val rel = "com/example/App.class"
        val bytes = "v1".toByteArray()
        // Manifest claims a sha that doesn't match the bytes → integrity fails.
        val entry = ReloadEntry(rel, ReloadChangeType.MODIFIED, sha256Wrong(), bytes.size.toLong())
        val batch = ReloadBatch(listOf(entry), ClassDiff.batchSha256(listOf(entry)))

        val r = applier.apply(batch, mapOf(rel to bytes))
        assertEquals(ReloadOutcome.REJECTED, r.outcome)
        assertTrue(coord.applied == null)
        assertFalse(Files.exists(hotDir.resolve(rel)), "no bytes should be written on a rejected batch")
    }

    @Test
    fun `applies a valid batch, writes the class into the hot dir, and calls the coordinator`() {
        val hotDir = Files.createTempDirectory("hot")
        val coord = FakeCoordinator(ReloadOutcome.APPLIED)
        val applier = ReloadApplier(isHotRunning = { true }, hotDir = { hotDir }, coordinator = coord)

        val rel = "com/example/App.class"
        val bytes = "class-bytes-v2".toByteArray()
        val (batch, byPath) = goodBatch(rel, bytes)

        val r = applier.apply(batch, byPath)
        assertEquals(ReloadOutcome.APPLIED, r.outcome)
        assertTrue(Files.exists(hotDir.resolve(rel)))
        assertEquals(bytes.toList(), Files.readAllBytes(hotDir.resolve(rel)).toList())
        assertEquals(mapOf(rel to ReloadChangeType.MODIFIED), coord.applied)
    }

    @Test
    fun `rejects a path that escapes the hot dir`() {
        val hotDir = Files.createTempDirectory("hot")
        val coord = FakeCoordinator()
        val applier = ReloadApplier(isHotRunning = { true }, hotDir = { hotDir }, coordinator = coord)

        val bytes = "x".toByteArray()
        val entry = ReloadEntry("../escape.class", ReloadChangeType.MODIFIED, sha(bytes), bytes.size.toLong())
        val batch = ReloadBatch(listOf(entry), ClassDiff.batchSha256(listOf(entry)))

        val r = applier.apply(batch, mapOf("../escape.class" to bytes))
        assertEquals(ReloadOutcome.REJECTED, r.outcome)
        assertTrue(coord.applied == null)
    }

    private fun sha256Wrong() = "0".repeat(64)
}
