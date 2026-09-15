package uz.disastrouspumpkin.wdb.plugin

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import uz.disastrouspumpkin.wdb.client.mcpServerCommand
import uz.disastrouspumpkin.wdb.client.upsertUserMcpServer
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * One-click install + register of the wdb MCP server for Claude Code (change add-plugin-mcp-install):
 * unzip the downloaded `wdb-mcp` release into `~/.wdb/mcp/`, then register its launcher at user scope
 * in `~/.claude.json` — either by a safe direct JSON edit (via wdb-client's [upsertUserMcpServer]) or
 * by `claude mcp add`, chosen by the operator with warnings shown first, and a copy-to-clipboard
 * fallback that never corrupts the config. Download/verify is done by [ReleaseSource].
 */
object McpInstall {
    enum class Outcome { INSTALLED, ALREADY, FALLBACK, CANCELLED, FAILED }

    private const val SERVER = "wdb"
    private val installDir: Path = Path.of(System.getProperty("user.home"), ".wdb", "mcp")
    private val claudeJson: Path = Path.of(System.getProperty("user.home"), ".claude.json")
    private val isWindows get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Unzip [zip] into `~/.wdb/mcp/` (replacing any prior `wdb-mcp/` subtree); return the launcher path. */
    fun unzipLauncher(zip: Path): Path {
        val sub = installDir.resolve("wdb-mcp")
        if (Files.exists(sub)) sub.toFile().deleteRecursively()
        Files.createDirectories(installDir)
        ZipInputStream(Files.newInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = installDir.resolve(e.name).normalize()
                require(out.startsWith(installDir)) { "zip entry escapes install dir: ${e.name}" } // zip-slip guard
                if (e.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out).use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
        return installDir.resolve("wdb-mcp/bin/wdb-mcp.bat")
    }

    /** A JDK is discoverable via JAVA_HOME or `java` on PATH (the launcher `.bat` needs Java at run time). */
    fun jdkAvailable(): Boolean {
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
            val exe = Path.of(home, "bin", if (isWindows) "java.exe" else "java")
            if (Files.isRegularFile(exe)) return true
        }
        return PathEnvironmentVariableUtil.findInPath("java") != null
    }

    fun claudeCliAvailable(): Boolean = PathEnvironmentVariableUtil.findInPath("claude") != null

    /** The launcher currently registered for `wdb` in `~/.claude.json`, or null. */
    fun existingWdbCommand(): String? =
        mcpServerCommand(runCatching { Files.readString(claudeJson) }.getOrNull(), SERVER)

    /**
     * EDT: show warnings, let the operator pick a registration method (or cancel), and write.
     * Idempotent — an existing `wdb` entry requires an explicit confirm before replacing.
     */
    fun register(project: Project, launcher: Path): Outcome {
        val launcherFwd = launcher.toString().replace('\\', '/')
        val existing = existingWdbCommand()

        if (existing != null) {
            val replace = Messages.showYesNoDialog(
                project,
                "wdb is already registered:\n    $existing\n\nReplace it with:\n    $launcherFwd ?",
                "wdb MCP already registered",
                "Replace", "Cancel", Messages.getQuestionIcon(),
            )
            if (replace != Messages.YES) return Outcome.ALREADY
        }

        val warnings = buildList {
            if (!jdkAvailable()) add("• No JDK found (JAVA_HOME / PATH) — the launcher needs Java 21 to run.")
            add("• Launcher: $launcherFwd")
            add("• Scope: user (visible from every project).")
        }.joinToString("\n")

        val cliAvailable = claudeCliAvailable()
        val options = if (cliAvailable) {
            arrayOf("Edit ~/.claude.json", "Run claude mcp add", "Copy command")
        } else {
            arrayOf("Edit ~/.claude.json", "Copy command")
        }
        val choice = Messages.showDialog(
            project,
            "Register the wdb MCP server for Claude Code.\n\n$warnings",
            "Install wdb MCP server",
            options, 0, Messages.getInformationIcon(),
        )
        return when {
            choice < 0 -> Outcome.CANCELLED
            options[choice].startsWith("Edit") -> if (registerByEdit(launcherFwd)) Outcome.INSTALLED else fallback(project, launcherFwd)
            options[choice].startsWith("Run") -> if (registerByCli(launcherFwd)) Outcome.INSTALLED else fallback(project, launcherFwd)
            else -> fallback(project, launcherFwd)
        }
    }

    /** Direct `~/.claude.json` edit; false (→ fallback) if the file is unreadable/unexpected. */
    fun registerByEdit(launcherFwd: String): Boolean = runCatching {
        val current = runCatching { Files.readString(claudeJson) }.getOrNull()
        val updated = upsertUserMcpServer(current, SERVER, launcherFwd) // throws on non-object → caught → false
        Files.createDirectories(claudeJson.parent)
        Files.writeString(claudeJson, updated)
        true
    }.getOrDefault(false)

    /** `claude mcp add wdb -s user -- <launcher>`; true on exit 0. */
    fun registerByCli(launcher: String): Boolean = runCatching {
        ProcessBuilder("claude", "mcp", "add", SERVER, "-s", "user", "--", launcher)
            .redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun fallback(project: Project, launcher: String): Outcome {
        CopyPasteManager.getInstance().setContents(StringSelection("claude mcp add $SERVER -s user -- \"$launcher\""))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(claudeJson)?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
        return Outcome.FALLBACK
    }
}
