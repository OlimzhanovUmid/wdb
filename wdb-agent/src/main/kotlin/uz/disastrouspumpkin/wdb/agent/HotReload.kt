@file:OptIn(
    org.jetbrains.compose.reload.ExperimentalHotReloadApi::class,
    org.jetbrains.compose.reload.DelicateHotReloadApi::class,
)

package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.client.ClassDiff
import uz.disastrouspumpkin.wdb.protocol.ReloadBatch
import uz.disastrouspumpkin.wdb.protocol.ReloadChangeType
import uz.disastrouspumpkin.wdb.protocol.ReloadOutcome
import uz.disastrouspumpkin.wdb.protocol.ReloadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.reload.core.getBlocking
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationServer
import org.jetbrains.compose.reload.orchestration.sendBlocking
import org.jetbrains.compose.reload.orchestration.startOrchestrationServer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Verifies a reload batch and applies it to the live hot app (spec compose-hot-reload).
 * Rejects (app untouched) when no hot run is active or integrity fails; otherwise writes the
 * class deltas into the hot dir (zip-slip guarded) and asks the coordinator to redefine.
 * State is injected as lambdas so this is unit-testable without spawning a real hot process.
 */
internal class ReloadApplier(
    private val isHotRunning: () -> Boolean,
    private val hotDir: () -> Path?,
    private val coordinator: HotReloadCoordinator?,
    private val lock: ReentrantLock = ReentrantLock(),
) {
    fun apply(batch: ReloadBatch, bytesByPath: Map<String, ByteArray>): ReloadResult {
        val coord = coordinator
        val dir = hotDir()
        if (coord == null || dir == null || !isHotRunning()) {
            return ReloadResult(ReloadOutcome.REJECTED, "app is not running in hot-reload mode")
        }
        if (ClassDiff.batchSha256(batch.entries) != batch.batchSha256) {
            return ReloadResult(ReloadOutcome.REJECTED, "reload batch manifest failed its integrity check")
        }
        for (entry in batch.entries) {
            if (entry.changeType == ReloadChangeType.REMOVED) continue
            val bytes = bytesByPath[entry.relPath]
                ?: return ReloadResult(ReloadOutcome.REJECTED, "missing bytes for ${entry.relPath}")
            if (sha256Hex(bytes) != entry.sha256) {
                return ReloadResult(ReloadOutcome.REJECTED, "class ${entry.relPath} failed its integrity check")
            }
        }
        return lock.withLock {
            val root = dir.normalize()
            for (entry in batch.entries) {
                val dest = root.resolve(entry.relPath).normalize()
                if (!dest.startsWith(root)) {
                    return@withLock ReloadResult(ReloadOutcome.REJECTED, "illegal reload path ${entry.relPath}")
                }
                when (entry.changeType) {
                    ReloadChangeType.REMOVED -> Files.deleteIfExists(dest)
                    else -> {
                        Files.createDirectories(dest.parent)
                        Files.write(dest, bytesByPath.getValue(entry.relPath))
                    }
                }
            }
            coord.applyReload(batch.entries.associate { it.relPath to it.changeType })
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Locate the bundled CHR `-javaagent` jar next to the agent's own jars in the app-image
 * (`app/hot-reload-agent-*.jar`), so hot mode can pass `-javaagent:<it>`. Null in dev/test
 * runs where it isn't bundled (hot mode then stays disabled).
 */
fun detectHotReloadAgentJar(): Path? {
    val cp = System.getProperty("java.class.path") ?: return null
    val firstJar = cp.split(java.io.File.pathSeparatorChar).firstOrNull { it.endsWith(".jar") } ?: return null
    val dir = runCatching { Path.of(firstJar).toAbsolutePath().parent }.getOrNull() ?: return null
    if (!Files.isDirectory(dir)) return null
    return Files.list(dir).use { stream ->
        stream.filter {
            val n = it.fileName.toString()
            n.startsWith("hot-reload-agent") && n.endsWith(".jar")
        }.findFirst().orElse(null)
    }
}

/**
 * Locate the bundled CHR runtime jars in the app-image (the `app/devtools` dir). Prepended to the hot
 * app's classpath so the agent's premain activates the devtools handlers (design D1 of
 * add-plugin-devtools). Empty in dev/test runs where they aren't bundled (devtools then disabled).
 */
fun detectDevtoolsRuntimeJars(): List<Path> {
    val cp = System.getProperty("java.class.path") ?: return emptyList()
    val firstJar = cp.split(java.io.File.pathSeparatorChar).firstOrNull { it.endsWith(".jar") } ?: return emptyList()
    val appDir = runCatching { Path.of(firstJar).toAbsolutePath().parent }.getOrNull() ?: return emptyList()
    val devtoolsDir = appDir.resolve("devtools")
    if (!Files.isDirectory(devtoolsDir)) return emptyList()
    return Files.list(devtoolsDir).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList()
    }
}

/**
 * Drives the Compose Hot Reload half of a hot run (design D1/D2): hosts the orchestration
 * server the app's CHR agent connects to, and turns a wdb reload batch into a CHR
 * `ReloadClassesRequest` that redefines classes in the live JVM. Abstracted behind an
 * interface so [AgentServer]'s reload logic is unit-testable with a fake.
 */
interface HotReloadCoordinator {
    /** Start (or reuse) the orchestration server and return the launch descriptor for the app. */
    fun beginHotRun(): HotLaunch

    /**
     * Emit a reload for classes already written under the hot dir, keyed by classpath-relative path.
     * Returns APPLIED on a successful live redefine, FAILED (with the app's error message as the
     * reason) when the change could not be hot-applied, or REJECTED when no hot run is active.
     */
    fun applyReload(changed: Map<String, ReloadChangeType>): ReloadResult

    /** Stop the orchestration server (the hot app was stopped). */
    fun endHotRun()

    // --- Devtools (change add-plugin-devtools): inspect / interact with the hot app's UI ---

    /** PNG bytes of the hot app's current screen, or null if no hot run / no answer. */
    fun screenshot(): ByteArray?

    /** The hot app's semantic tree as JSON, or null if no hot run / no answer. */
    fun semanticTree(): String?

    /** Dispatch [action] to semantic node [nodeId]; false if no hot run / not applied. */
    fun uiAction(nodeId: Int, action: uz.disastrouspumpkin.wdb.protocol.UiActionRequest): Boolean
}

/**
 * Real coordinator over CHR's `hot-reload-orchestration`. Compiles against CHR 1.2.0; its
 * live redefine behavior is verified on a wall machine (tasks 8.x), not in unit tests.
 */
class ChrHotReloadCoordinator(
    private val hotDir: Path,
    private val agentJar: Path,
    private val reloadTimeoutMs: Long = 15_000,
) : HotReloadCoordinator {
    @Volatile private var server: OrchestrationServer? = null

    override fun beginHotRun(): HotLaunch {
        Files.createDirectories(hotDir)
        val srv = server ?: startOrchestrationServer().also { server = it }
        val port = srv.port.getBlocking().leftOrNull()
            ?: error("orchestration server did not report a port")
        return HotLaunch(orchestrationPort = port, hotDir = hotDir, agentJar = agentJar)
    }

    override fun applyReload(changed: Map<String, ReloadChangeType>): ReloadResult {
        val srv = server ?: return ReloadResult(ReloadOutcome.REJECTED, "no hot run")
        val fileMap = changed.entries.associate { (rel, ct) -> hotDir.resolve(rel).toFile() to ct.toChr() }
        val request = OrchestrationMessage.ReloadClassesRequest(fileMap)
        return runBlocking {
            // Start collecting the result BEFORE sending, so a fast reply can't be missed.
            val done = CompletableDeferred<OrchestrationMessage.ReloadClassesResult>()
            val collector = launch {
                srv.messages.collect { msg ->
                    if (msg is OrchestrationMessage.ReloadClassesResult && msg.reloadRequestId == request.messageId) {
                        done.complete(msg)
                    }
                }
            }
            val sent = srv.sendBlocking(request).leftOrNull()
            val result = if (sent == null) null else withTimeoutOrNull(reloadTimeoutMs) { done.await() }
            collector.cancel()
            when {
                sent == null -> ReloadResult(ReloadOutcome.FAILED, "orchestration send failed")
                result == null -> ReloadResult(ReloadOutcome.FAILED, "no reload result within ${reloadTimeoutMs}ms")
                result.isSuccess -> ReloadResult(ReloadOutcome.APPLIED)
                else -> ReloadResult(ReloadOutcome.FAILED, result.errorMessage ?: "app rejected the reload")
            }
        }
    }

    override fun endHotRun() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    override fun screenshot(): ByteArray? {
        val req = OrchestrationMessage.ScreenshotRequest()
        return requestReply(req) { m ->
            if (m is OrchestrationMessage.ScreenshotResult && m.screenshotRequestId == req.messageId && m.isSuccess) m.data else null
        }
    }

    override fun semanticTree(): String? {
        val req = OrchestrationMessage.SemanticTreeRequest()
        return requestReply(req) { m ->
            if (m is OrchestrationMessage.SemanticTreeResult && m.semanticTreeRequestId == req.messageId) m.tree else null
        }
    }

    override fun uiAction(nodeId: Int, action: uz.disastrouspumpkin.wdb.protocol.UiActionRequest): Boolean {
        val chrAction = when (action.kind) {
            uz.disastrouspumpkin.wdb.protocol.UiActionKind.CLICK -> OrchestrationMessage.UIAction.Click
            uz.disastrouspumpkin.wdb.protocol.UiActionKind.LONG_CLICK -> OrchestrationMessage.UIAction.LongClick
            uz.disastrouspumpkin.wdb.protocol.UiActionKind.SET_TEXT -> OrchestrationMessage.UIAction.SetText(action.text)
            uz.disastrouspumpkin.wdb.protocol.UiActionKind.SCROLL_BY -> OrchestrationMessage.UIAction.ScrollBy(action.dx, action.dy)
            uz.disastrouspumpkin.wdb.protocol.UiActionKind.SCROLL_TO_INDEX -> OrchestrationMessage.UIAction.ScrollToIndex(action.index)
        }
        val req = OrchestrationMessage.UIActionRequest(nodeId, chrAction)
        return requestReply(req) { m ->
            if (m is OrchestrationMessage.UIActionResult && m.uiActionRequestId == req.messageId) m.isSuccess else null
        } ?: false
    }

    /** Send [request] and await the first message [extract] maps to a non-null result (design D2). */
    private fun <R> requestReply(request: OrchestrationMessage, timeoutMs: Long = 15_000, extract: (OrchestrationMessage) -> R?): R? {
        val srv = server ?: return null
        return runBlocking {
            val done = CompletableDeferred<R>()
            val collector = launch {
                srv.messages.collect { msg -> extract(msg)?.let { done.complete(it) } }
            }
            val sent = srv.sendBlocking(request).leftOrNull()
            val result = if (sent == null) null else withTimeoutOrNull(timeoutMs) { done.await() }
            collector.cancel()
            result
        }
    }

    private fun ReloadChangeType.toChr(): OrchestrationMessage.ReloadClassesRequest.ChangeType = when (this) {
        ReloadChangeType.ADDED -> OrchestrationMessage.ReloadClassesRequest.ChangeType.Added
        ReloadChangeType.MODIFIED -> OrchestrationMessage.ReloadClassesRequest.ChangeType.Modified
        ReloadChangeType.REMOVED -> OrchestrationMessage.ReloadClassesRequest.ChangeType.Removed
    }
}
