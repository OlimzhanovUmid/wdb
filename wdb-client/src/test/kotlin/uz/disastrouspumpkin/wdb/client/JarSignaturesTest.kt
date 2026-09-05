package uz.disastrouspumpkin.wdb.client

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Strip stale signature files on push (change add-push-signature-stripping). */
class JarSignaturesTest {

    private fun jarOf(vararg entries: Pair<String, String>): Path {
        val jar = Files.createTempFile("wdb-test", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        jar.toFile().deleteOnExit()
        return jar
    }

    private fun names(jar: Path): Set<String> =
        ZipFile(jar.toFile()).use { zf -> zf.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun strips_signature_files_keeps_rest() {
        val jar = jarOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
            "META-INF/SIGNER.SF" to "sig",
            "META-INF/SIGNER.DSA" to "sig",
            "META-INF/VENDOR.RSA" to "sig",
            "com/x/A.class" to "class",
        )
        val res = stripJarSignatures(jar)!!
        assertEquals(setOf("META-INF/SIGNER.SF", "META-INF/SIGNER.DSA", "META-INF/VENDOR.RSA"), res.removed.toSet())
        val kept = names(res.jar)
        assertTrue("META-INF/MANIFEST.MF" in kept)
        assertTrue("com/x/A.class" in kept)
        assertFalse(kept.any { it.endsWith(".SF") || it.endsWith(".RSA") || it.endsWith(".DSA") })
        Files.deleteIfExists(res.jar)
    }

    @Test
    fun unsigned_jar_returns_null() {
        val jar = jarOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n", "com/x/A.class" to "class")
        assertNull(stripJarSignatures(jar))
    }

    @Test
    fun nested_meta_inf_not_treated_as_signature() {
        // A ".SF" that is not directly under META-INF/ is a normal resource, not a signature file.
        val jar = jarOf("META-INF/services/foo.SF" to "x", "com/x/A.class" to "class")
        assertNull(stripJarSignatures(jar))
    }

    @Test
    fun push_transfers_cleaned_jar_and_reports() = runBlocking {
        val signed = jarOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
            "META-INF/SIGNER.SF" to "sig",
            "com/x/A.class" to "class",
        )
        val expectedCleanSha = stripJarSignatures(signed)!!.let { val s = sha256(it.jar); Files.deleteIfExists(it.jar); s }
        FakeAgent().use { fake ->
            val notices = mutableListOf<String>()
            val r = pushJar(fake.address, signed, "Main", onNotice = { notices += it })
            assertTrue(r.ok)
            assertEquals(1, notices.size)
            assertTrue("signature" in notices.single())
            // Agent verified the cleaned bytes, not the signed original.
            assertEquals(expectedCleanSha, r.deployedSha)
            assertNotEquals(sha256(signed), r.deployedSha)
        }
    }

    @Test
    fun push_unsigned_jar_is_unchanged_and_silent() = runBlocking {
        val plain = jarOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n", "com/x/A.class" to "class")
        FakeAgent().use { fake ->
            val notices = mutableListOf<String>()
            val r = pushJar(fake.address, plain, "Main", onNotice = { notices += it })
            assertTrue(r.ok)
            assertTrue(notices.isEmpty())
            assertEquals(sha256(plain), r.deployedSha)
        }
    }
}
