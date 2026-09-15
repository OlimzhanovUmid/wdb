package uz.disastrouspumpkin.wdb.mcp

import uz.disastrouspumpkin.wdb.client.ComponentRelease
import uz.disastrouspumpkin.wdb.client.parseReleaseManifest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Pulls the release manifest (`latest.json`) and agent installer from the GitHub release, using the
 * JDK's `java.net.http` (wdb-mcp is a plain JVM app — no IntelliJ `HttpRequests`). The MCP host does
 * the download, then pushes to the agent over the LAN (same plugin-mediated-pull model as the plugin).
 */
object ReleaseFetch {
    private const val MANIFEST_URL =
        "https://github.com/OlimzhanovUmid/wdb/releases/latest/download/latest.json"

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    class IntegrityException(message: String) : RuntimeException(message)

    /** The latest published agent, or null if the manifest is unreachable/unparseable. */
    fun latestAgent(): ComponentRelease? = runCatching {
        val resp = http.send(
            HttpRequest.newBuilder(URI.create(MANIFEST_URL)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (resp.statusCode() !in 200..299) return null
        parseReleaseManifest(resp.body())["agent"]
    }.getOrNull()

    /** Download [component] to a temp file, verify size + sha256, return the path. Caller deletes it. */
    fun downloadVerified(component: ComponentRelease): Path {
        val dest = Files.createTempFile("wdb-agent-", ".zip")
        try {
            http.send(
                HttpRequest.newBuilder(URI.create(component.url)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(dest),
            )
            if (Files.size(dest) != component.size || sha256(dest) != component.sha256.lowercase()) {
                throw IntegrityException("integrity check failed for ${component.asset} (size/sha256 mismatch)")
            }
            return dest
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(dest) }
            throw e
        }
    }

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
