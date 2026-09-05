package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.PushResult
import uz.disastrouspumpkin.wdb.protocol.ReloadBatch
import uz.disastrouspumpkin.wdb.protocol.ReloadChangeType
import uz.disastrouspumpkin.wdb.protocol.ReloadEntry
import uz.disastrouspumpkin.wdb.protocol.ReloadOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension

/**
 * A snapshot of a compiled-classes directory: classpath-relative path (forward slashes)
 * -> sha256 of the `.class` bytes. Comparing two snapshots yields the reload delta so a
 * watch loop pushes only what changed.
 */
typealias ClassSnapshot = Map<String, String>

/**
 * Diffs a dev-side classes directory against the last-pushed snapshot and builds the
 * bytes for a reload. Paths are classpath-relative with forward slashes so they match the
 * remote hot-classpath layout regardless of OS.
 */
object ClassDiff {

    /** Hash every `.class` file under [classesDir]; empty map if the dir does not exist. */
    fun snapshot(classesDir: Path): ClassSnapshot {
        if (!Files.isDirectory(classesDir)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        Files.walk(classesDir).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.extension == "class" }
                .sorted()
                .forEach { p -> out[relPath(classesDir, p)] = sha256Bytes(Files.readAllBytes(p)) }
        }
        return out
    }

    /** ADDED (in new only), MODIFIED (sha differs), REMOVED (in old only). */
    fun diff(old: ClassSnapshot, new: ClassSnapshot): List<ReloadEntry> {
        val entries = ArrayList<ReloadEntry>()
        for ((path, sha) in new) {
            val prev = old[path]
            when {
                prev == null -> entries += ReloadEntry(path, ReloadChangeType.ADDED, sha)
                prev != sha -> entries += ReloadEntry(path, ReloadChangeType.MODIFIED, sha)
            }
        }
        for (path in old.keys) if (path !in new) entries += ReloadEntry(path, ReloadChangeType.REMOVED)
        return entries.sortedBy { it.relPath }
    }

    /**
     * Build a [ReloadPayload] for the changes since [previous] and return it alongside the
     * fresh snapshot (the new baseline for the next push). Fills each ADDED/MODIFIED entry's
     * size from the bytes read; REMOVED entries carry none.
     */
    fun buildPayload(classesDir: Path, previous: ClassSnapshot): Pair<ReloadPayload, ClassSnapshot> {
        val now = snapshot(classesDir)
        val rawEntries = diff(previous, now)
        val bytesByPath = LinkedHashMap<String, ByteArray>()
        val entries = rawEntries.map { e ->
            if (e.changeType == ReloadChangeType.REMOVED) return@map e
            val bytes = Files.readAllBytes(classesDir.resolve(e.relPath))
            bytesByPath[e.relPath] = bytes
            e.copy(size = bytes.size.toLong())
        }
        val payload = ReloadPayload(ReloadBatch(entries, batchSha256(entries)), bytesByPath)
        return payload to now
    }

    /** Deterministic hash over the batch's entries (path + change type + class sha), for integrity. */
    fun batchSha256(entries: List<ReloadEntry>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in entries.sortedBy { it.relPath }) {
            md.update(e.relPath.toByteArray())
            md.update(e.changeType.name.toByteArray())
            md.update(e.sha256.toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun relPath(root: Path, file: Path): String =
        root.relativize(file).toString().replace('\\', '/')
}

internal fun sha256Bytes(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(bytes)
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Per-target outcome of a reload, including the redeploy fallback (design D6). */
sealed interface ReloadReport {
    val target: String

    /** Classes were live-redefined; the app kept running. */
    data class Applied(override val target: String, val classCount: Int) : ReloadReport

    /** Not applicable (app not in hot mode, or integrity) — app untouched, no redeploy. */
    data class Rejected(override val target: String, val reason: String?) : ReloadReport

    /** Hot-apply failed and the client fell back to a full redeploy + restart. */
    data class Redeployed(override val target: String, val push: PushResult) : ReloadReport

    /** Hot-apply failed and no redeploy fallback was available. */
    data class Failed(override val target: String, val reason: String?) : ReloadReport
}

/**
 * Push a reload to one target and, on a [ReloadOutcome.FAILED] result, fall back to a full
 * redeploy + restart via [redeploy] (design D6). A [ReloadOutcome.REJECTED] result (not hot /
 * integrity) does NOT trigger a redeploy — the app is untouched and the fix is to retry.
 */
suspend fun WdbClient.reloadOrRedeploy(
    target: String,
    payload: ReloadPayload,
    host: AgentAddress? = null,
    redeploy: (suspend () -> PushResult)? = null,
): ReloadReport {
    val result = reload(target, payload, host)
    return when (result.outcome) {
        ReloadOutcome.APPLIED -> ReloadReport.Applied(target, payload.batch.entries.size)
        ReloadOutcome.REJECTED -> ReloadReport.Rejected(target, result.reason)
        ReloadOutcome.FAILED ->
            if (redeploy != null) ReloadReport.Redeployed(target, redeploy())
            else ReloadReport.Failed(target, result.reason)
    }
}
