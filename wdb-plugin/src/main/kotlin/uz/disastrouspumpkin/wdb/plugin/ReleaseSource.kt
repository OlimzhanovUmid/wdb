package uz.disastrouspumpkin.wdb.plugin

import com.intellij.util.io.HttpRequests
import uz.disastrouspumpkin.wdb.client.ComponentRelease
import uz.disastrouspumpkin.wdb.client.parseReleaseManifest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Fetches the published release manifest (`latest.json`) and downloads + verifies release assets,
 * caching each per version. HTTP lives here (IntelliJ [HttpRequests], no extra plugin dependency);
 * manifest parsing + integrity data come from wdb-client. This is the GitHub-download core the
 * release-consuming plugin actions share (agent-github-pull first; cli/mcp install later).
 */
object ReleaseSource {
    // The pipeline publishes both index files at GitHub's stable "latest release" URL.
    private const val MANIFEST_URL =
        "https://github.com/OlimzhanovUmid/wdb/releases/latest/download/latest.json"

    private val cacheDir: Path = Path.of(System.getProperty("user.home"), ".wdb", "release-cache")

    class IntegrityException(message: String) : RuntimeException(message)

    /** Fetch + parse `latest.json`. Returns null when unreachable/unparseable so callers degrade quietly. */
    fun latestManifest(): Map<String, ComponentRelease>? = runCatching {
        parseReleaseManifest(HttpRequests.request(MANIFEST_URL).readString())
    }.getOrNull()

    /**
     * Download [component]'s asset into the per-version cache, verifying size + sha256 against the
     * manifest. Returns the cached file, reused when it already matches (so a fleet rollout downloads
     * once). Throws [IntegrityException] on a size/hash mismatch (a corrupt cache is re-downloaded).
     */
    fun downloadVerified(component: ComponentRelease, onProgress: ((Long, Long) -> Unit)? = null): Path {
        Files.createDirectories(cacheDir)
        val target = cacheDir.resolve(component.asset)
        if (Files.exists(target) && matches(target, component)) return target

        HttpRequests.request(component.url).connect { request ->
            Files.newOutputStream(target).use { out ->
                val input = request.inputStream
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress?.invoke(read, component.size)
                }
            }
        }
        if (!matches(target, component)) {
            runCatching { Files.deleteIfExists(target) }
            throw IntegrityException("integrity check failed for ${component.asset} (size/sha256 mismatch)")
        }
        return target
    }

    private fun matches(file: Path, c: ComponentRelease): Boolean =
        Files.size(file) == c.size && sha256(file).equals(c.sha256, ignoreCase = true)

    private fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
