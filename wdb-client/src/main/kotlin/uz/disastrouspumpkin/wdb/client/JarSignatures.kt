package uz.disastrouspumpkin.wdb.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A jar rewritten without its signature files: the temp [jar] and the [removed] entry names. */
internal data class StripResult(val jar: Path, val removed: List<String>)

/** A JAR signature file: directly under `META-INF/` with a `.SF`/`.RSA`/`.DSA`/`.EC` extension. */
private fun isSignatureEntry(name: String): Boolean {
    if (!name.startsWith("META-INF/", ignoreCase = true)) return false
    val rest = name.substring("META-INF/".length)
    if (rest.isEmpty() || rest.contains('/')) return false // directly under META-INF, not nested
    val ext = rest.substringAfterLast('.', "").uppercase()
    return ext == "SF" || ext == "RSA" || ext == "DSA" || ext == "EC"
}

/**
 * If [jar] carries JAR signature files (a signed dependency's `META-INF` `*.SF`, `*.RSA`, `*.DSA`, `*.EC`),
 * write a temp copy of the jar without them and return it plus the removed names; otherwise null.
 * A signed jar cannot pass verification after a fat-jar repack, so those files only cause a
 * `SecurityException: Invalid signature file digest` at class-load time (change add-push-signature-stripping).
 * Each surviving entry keeps its original compression method.
 */
internal fun stripJarSignatures(jar: Path): StripResult? {
    val removed = ArrayList<String>()
    ZipFile(jar.toFile()).use { zf ->
        val e = zf.entries()
        while (e.hasMoreElements()) {
            val name = e.nextElement().name
            if (isSignatureEntry(name)) removed += name
        }
    }
    if (removed.isEmpty()) return null

    val tmp = Files.createTempFile("wdb-push", ".jar")
    ZipFile(jar.toFile()).use { zf ->
        ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (isSignatureEntry(entry.name)) continue
                val out = ZipEntry(entry.name).apply {
                    method = entry.method
                    time = entry.time
                    if (entry.method == ZipEntry.STORED) {
                        size = entry.size
                        compressedSize = entry.compressedSize
                        crc = entry.crc
                    }
                }
                zos.putNextEntry(out)
                zf.getInputStream(entry).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
    return StripResult(tmp, removed)
}
