package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Marks an update in flight; the watchdog reverts to [previousVersion] if the new
 * agent never confirms health by clearing this marker (design D5). [newVersion] is
 * the version switched to.
 */
@Serializable
data class PendingUpdate(
    val previousVersion: String? = null,
    val newVersion: String,
    val createdMillis: Long,
)

/**
 * The versioned agent install layout (design D2, launcher-stub variant):
 *
 *   <base>/agent/versions/<version>/   one extracted app-image per version
 *   <base>/agent/current-version       text: the version the launcher runs
 *   <base>/agent/previous-version      text: the retained rollback version
 *   <base>/agent/launch.cmd            stub the autostart task runs; reads current-version
 *   <base>/agent/pending-update.json   in-flight update marker
 *
 * Switching versions is a plain write to `current-version` (no junction), so it works
 * from any process and cannot be blocked by an executable running through a link.
 */
class AgentInstallLayout(val base: Path) {
    val agentDir: Path get() = base.resolve("agent")
    val versionsDir: Path get() = agentDir.resolve("versions")
    val launchCmd: Path get() = agentDir.resolve("launch.cmd")
    private val currentVersionFile: Path get() = agentDir.resolve("current-version")
    private val previousVersionFile: Path get() = agentDir.resolve("previous-version")
    val markerFile: Path get() = agentDir.resolve("pending-update.json")

    fun versionDir(version: String): Path = versionsDir.resolve(version)

    fun currentVersion(): String? = readText(currentVersionFile)
    fun previousVersion(): String? = readText(previousVersionFile)

    /** Extract an app-image zip into `versions/<version>/`, replacing any prior copy. */
    fun extract(zip: Path, version: String): Path {
        val dir = versionDir(version)
        if (Files.exists(dir)) deleteRecursively(dir)
        Files.createDirectories(dir)
        ZipInputStream(Files.newInputStream(zip)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = dir.resolve(entry.name).normalize()
                require(out.startsWith(dir)) { "zip entry escapes target: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out).use { zin.copyTo(it) }
                }
                entry = zin.nextEntry
            }
        }
        return dir
    }

    /** Promote [version] to current (a plain file write), demoting the old current to previous, then GC. */
    fun switchTo(version: String) {
        val old = currentVersion()
        Files.createDirectories(agentDir)
        if (old != null && old != version) Files.writeString(previousVersionFile, old)
        Files.writeString(currentVersionFile, version)
        gc()
    }

    /** Swap current<->previous. Returns the new current, or null if none. */
    fun revertToPrevious(): String? {
        val prev = previousVersion() ?: return null
        val cur = currentVersion()
        Files.writeString(currentVersionFile, prev)
        if (cur != null) Files.writeString(previousVersionFile, cur) else Files.deleteIfExists(previousVersionFile)
        return prev
    }

    /**
     * Write the launcher stub the autostart task runs. It reads `current-version` at
     * launch and starts that version's exe — so an update only rewrites a text file.
     */
    fun writeLauncher(machineName: String, jdwpPort: Int?) {
        Files.createDirectories(agentDir)
        val jdwpArg = if (jdwpPort != null) " --jdwp-port $jdwpPort" else ""
        val agent = agentDir.toString()
        val cmd = buildString {
            appendLine("@echo off")
            appendLine("setlocal")
            appendLine("set /p WDBV=<\"$agent\\current-version\"")
            appendLine("\"$agent\\versions\\%WDBV%\\wdb-agent.exe\" run --name \"$machineName\"$jdwpArg")
        }
        Files.writeString(launchCmd, cmd)
    }

    fun writeMarker(marker: PendingUpdate) {
        Files.createDirectories(agentDir)
        Files.write(markerFile, MessageCodec.encode(marker))
    }

    fun readMarker(): PendingUpdate? = runCatching {
        if (Files.exists(markerFile)) MessageCodec.decode<PendingUpdate>(Files.readAllBytes(markerFile)) else null
    }.getOrNull()

    fun clearMarker() {
        runCatching { Files.deleteIfExists(markerFile) }
    }

    /**
     * Append one timestamped line to `<agentDir>/agent-update.log` (change add-agent-selfupdate-logging).
     * Best-effort: never throws, so it is safe to call from the update/watchdog/boot paths. The line
     * carries the writing process's agent version + pid so the three self-update processes are
     * distinguishable in one file.
     */
    fun log(event: String) {
        runCatching {
            Files.createDirectories(agentDir)
            Files.writeString(
                agentDir.resolve("agent-update.log"),
                "${java.time.Instant.now()} [${BuildConfig.AGENT_VERSION}/pid ${ProcessHandle.current().pid()}] $event\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }

    /** Keep only current and previous version directories. */
    private fun gc() {
        val keep = setOfNotNull(currentVersion(), previousVersion())
        if (!Files.exists(versionsDir)) return
        Files.list(versionsDir).use { stream ->
            stream.filter { Files.isDirectory(it) && it.fileName.toString() !in keep }
                .forEach { runCatching { deleteRecursively(it) } }
        }
    }

    private fun readText(file: Path): String? =
        if (Files.exists(file)) Files.readString(file).trim().ifEmpty { null } else null

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
