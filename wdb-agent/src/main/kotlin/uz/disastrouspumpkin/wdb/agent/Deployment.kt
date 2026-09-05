package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import uz.disastrouspumpkin.wdb.protocol.PushManifest
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Persisted launch parameters for a deployment. */
@Serializable
data class DeploymentMeta(
    val sha: String,
    val jarName: String,
    val mainClass: String,
    val jvmArgs: List<String> = emptyList(),
    val programArgs: List<String> = emptyList(),
)

/** A committed deployment on disk. */
data class Deployment(val sha: String, val dir: Path, val jar: Path, val meta: DeploymentMeta)

/** The current/previous deployment pointers, persisted together so the switch is atomic. */
@Serializable
private data class Pointers(val current: String? = null, val previous: String? = null)

class IntegrityException(message: String) : Exception(message)

/**
 * Content-addressed deployment store (design D8). Each JAR lives in
 * `deployments/<sha>/`; a `current` pointer is switched atomically only after
 * a full, verified transfer, so a running app is never disturbed by an in-flight
 * push and an interrupted push cannot corrupt the current deployment. The prior
 * deployment is retained for rollback; older ones are GC'd.
 */
class DeploymentStore(private val paths: AgentPaths) {

    init {
        paths.ensure()
    }

    /**
     * Verify [tempJar] against [manifest] and commit it as a new deployment.
     * Does NOT change the current pointer — call [makeCurrent] after a successful
     * launch decision. Returns the committed [Deployment].
     */
    fun commit(manifest: PushManifest, tempJar: Path): Deployment {
        val entry = manifest.entries.singleOrNull()
            ?: throw IntegrityException("v1 expects exactly one blob, got ${manifest.entries.size}")
        val actualSize = Files.size(tempJar)
        if (actualSize != entry.size) {
            throw IntegrityException("size mismatch: expected ${entry.size}, got $actualSize")
        }
        val actualSha = sha256(tempJar)
        if (actualSha != entry.sha256) {
            throw IntegrityException("checksum mismatch: expected ${entry.sha256}, got $actualSha")
        }

        val dir = paths.deploymentDir(actualSha)
        Files.createDirectories(dir)
        val jar = dir.resolve(entry.name)
        // If this exact deployment already exists, the on-disk jar is byte-identical
        // (same sha). Re-pushing the currently-running build would otherwise try to
        // overwrite a jar the running JVM has locked (Windows) and fail — so keep the
        // existing file and just refresh launch params below. Idempotent same-sha push.
        if (Files.exists(jar)) {
            Files.deleteIfExists(tempJar)
        } else {
            Files.move(tempJar, jar, StandardCopyOption.REPLACE_EXISTING)
        }
        val meta = DeploymentMeta(actualSha, entry.name, manifest.mainClass, manifest.jvmArgs, manifest.programArgs)
        Files.write(dir.resolve("meta.json"), MessageCodec.encode(meta))
        return Deployment(actualSha, dir, jar, meta)
    }

    /** Promote [sha] to current, demoting the old current to previous, then GC. */
    fun makeCurrent(sha: String) {
        val p = readPointers()
        val newPrevious = if (p.current != null && p.current != sha) p.current else p.previous
        writePointers(Pointers(current = sha, previous = newPrevious))
        gc()
    }

    fun current(): Deployment? = readPointers().current?.let(::load)

    fun previousSha(): String? = readPointers().previous

    /** Swap current<->previous. Returns the new current, or null if none to roll back to. */
    fun rollback(): Deployment? {
        val p = readPointers()
        val prev = p.previous ?: return null
        writePointers(Pointers(current = prev, previous = p.current))
        return current()
    }

    private fun load(sha: String): Deployment? {
        val dir = paths.deploymentDir(sha)
        val metaFile = dir.resolve("meta.json")
        if (!Files.exists(metaFile)) return null
        val meta = MessageCodec.decode<DeploymentMeta>(Files.readAllBytes(metaFile))
        return Deployment(sha, dir, dir.resolve(meta.jarName), meta)
    }

    /** Keep only the current and previous deployments; delete the rest. */
    private fun gc() {
        val p = readPointers()
        val keep = setOfNotNull(p.current, p.previous)
        if (!Files.exists(paths.deploymentsDir)) return
        Files.list(paths.deploymentsDir).use { stream ->
            stream.filter { Files.isDirectory(it) && it.fileName.toString() !in keep }
                .forEach { dir -> runCatching { deleteRecursively(dir) } }
        }
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun readPointers(): Pointers = runCatching {
        if (Files.exists(paths.pointersFile)) {
            MessageCodec.decode<Pointers>(Files.readAllBytes(paths.pointersFile))
        } else {
            Pointers()
        }
    }.getOrDefault(Pointers())

    /** Write both pointers in one atomic replace, so a crash can't leave a torn switch. */
    private fun writePointers(pointers: Pointers) {
        Files.createDirectories(paths.dataDir)
        val tmp = Files.createTempFile(paths.dataDir, "pointers", ".tmp")
        Files.write(tmp, MessageCodec.encode(pointers))
        try {
            Files.move(tmp, paths.pointersFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Throwable) {
            Files.move(tmp, paths.pointersFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
