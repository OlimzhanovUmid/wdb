package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.BlobEntry
import uz.disastrouspumpkin.wdb.protocol.PushManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeploymentTest {

    private fun store(): DeploymentStore = DeploymentStore(AgentPaths(Files.createTempDirectory("wdb-dep")))

    private fun jar(content: ByteArray): Path =
        Files.createTempFile("blob", ".jar").also { Files.write(it, content) }

    private fun manifestFor(jar: Path, name: String = "app.jar"): PushManifest =
        PushManifest(listOf(BlobEntry(name, sha256(jar), Files.size(jar))), mainClass = "M")

    @Test
    fun `commit then makeCurrent exposes the deployment`() {
        val s = store()
        val j = jar("v1".toByteArray())
        val dep = s.commit(manifestFor(j), j)
        s.makeCurrent(dep.sha)
        assertEquals(dep.sha, s.current()!!.sha)
        assertTrue(Files.exists(s.current()!!.jar))
    }

    @Test
    fun `checksum mismatch is rejected and current is untouched`() {
        val s = store()
        val good = jar("good".toByteArray())
        val depA = s.commit(manifestFor(good), good)
        s.makeCurrent(depA.sha)

        val bad = jar("bad".toByteArray())
        val lyingManifest = PushManifest(listOf(BlobEntry("app.jar", "deadbeef", Files.size(bad))), mainClass = "M")
        assertFailsWith<IntegrityException> { s.commit(lyingManifest, bad) }
        assertEquals(depA.sha, s.current()!!.sha) // unchanged
    }

    @Test
    fun `size mismatch is rejected`() {
        val s = store()
        val j = jar("12345".toByteArray())
        val wrongSize = PushManifest(listOf(BlobEntry("app.jar", sha256(j), 999)), mainClass = "M")
        assertFailsWith<IntegrityException> { s.commit(wrongSize, j) }
    }

    @Test
    fun `rollback swaps current and previous`() {
        val s = store()
        val a = jar("A".toByteArray()); val depA = s.commit(manifestFor(a), a); s.makeCurrent(depA.sha)
        val b = jar("B".toByteArray()); val depB = s.commit(manifestFor(b), b); s.makeCurrent(depB.sha)
        assertEquals(depB.sha, s.current()!!.sha)
        assertEquals(depA.sha, s.previousSha())

        val rolled = s.rollback()!!
        assertEquals(depA.sha, rolled.sha)
        assertEquals(depA.sha, s.current()!!.sha)
    }

    @Test
    fun `re-committing the same sha is idempotent and does not overwrite the existing jar`() {
        val s = store()
        val j1 = jar("same-content".toByteArray())
        val dep1 = s.commit(manifestFor(j1), j1)
        s.makeCurrent(dep1.sha)

        val j2 = jar("same-content".toByteArray()) // identical bytes -> identical sha
        val dep2 = s.commit(manifestFor(j2), j2)   // must not throw even though the jar already exists

        assertEquals(dep1.sha, dep2.sha)
        assertTrue(Files.exists(dep2.jar))
        assertTrue(Files.notExists(j2)) // the temp upload was consumed (deleted), not left behind
    }

    @Test
    fun `rollback with no previous returns null`() {
        val s = store()
        val a = jar("only".toByteArray()); val depA = s.commit(manifestFor(a), a); s.makeCurrent(depA.sha)
        assertNull(s.rollback())
    }

    @Test
    fun `gc keeps only current and previous`() {
        val paths = AgentPaths(Files.createTempDirectory("wdb-dep"))
        val s = DeploymentStore(paths)
        val shas = ArrayList<String>()
        for (c in listOf("A", "B", "C")) {
            val j = jar(c.toByteArray())
            val dep = s.commit(manifestFor(j), j)
            s.makeCurrent(dep.sha)
            shas += dep.sha
        }
        val remaining = Files.list(paths.deploymentsDir).use { stream ->
            stream.toList().map { it.fileName.toString() }.toSet()
        }
        // current = C, previous = B, A must be GC'd
        assertEquals(setOf(shas[1], shas[2]), remaining)
    }
}
