package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Streaming SHA-256 of a file, lowercase hex. */
fun sha256(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
