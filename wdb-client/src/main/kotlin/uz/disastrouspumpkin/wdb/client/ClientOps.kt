package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.AgentUpdateManifest
import uz.disastrouspumpkin.wdb.protocol.BlobEntry
import uz.disastrouspumpkin.wdb.protocol.ControlRequest
import uz.disastrouspumpkin.wdb.protocol.ControlResponse
import uz.disastrouspumpkin.wdb.protocol.Handshake
import uz.disastrouspumpkin.wdb.protocol.LogEvent
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import uz.disastrouspumpkin.wdb.protocol.PushManifest
import uz.disastrouspumpkin.wdb.protocol.PushResult
import uz.disastrouspumpkin.wdb.protocol.ScreenshotRequest
import uz.disastrouspumpkin.wdb.protocol.ScreenshotResponse
import uz.disastrouspumpkin.wdb.protocol.ReloadBatch
import uz.disastrouspumpkin.wdb.protocol.ReloadChangeType
import uz.disastrouspumpkin.wdb.protocol.ReloadResult
import uz.disastrouspumpkin.wdb.protocol.StreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Progress callback for a push: (bytesSent, totalBytes). */
typealias PushProgress = (Long, Long) -> Unit

/** Open a short-lived control connection, send one request, read one response. */
suspend fun sendControl(
    address: AgentAddress,
    request: ControlRequest,
    connectTimeoutMs: Int = Connection.DEFAULT_CONNECT_TIMEOUT_MS,
): ControlResponse {
    val conn = Connection.open(address, Handshake(StreamKind.CONTROL), connectTimeoutMs)
    return try {
        withCancellationClosing(conn) {
            conn.writeFrame(MessageCodec.encode(ControlRequest.serializer(), request))
            val bytes = conn.readFrame() ?: throw EOFException("no control response from agent")
            MessageCodec.decode(ControlResponse.serializer(), bytes)
        }
    } finally {
        conn.close()
    }
}

/**
 * Screenshot over CONTROL: send [ScreenshotRequest], read the [ScreenshotResponse] header, and on
 * `ok` read the trailing raw-PNG blob frame (change add-binary-screenshot-transport). Returns the PNG
 * bytes, or null when the agent reports no screenshot. The image is NOT base64 — it rides as a blob
 * frame after the header, so a full-HD screen fits under the frame cap without inflation.
 */
suspend fun screenshotControl(
    address: AgentAddress,
    connectTimeoutMs: Int = Connection.DEFAULT_CONNECT_TIMEOUT_MS,
): ByteArray? {
    val conn = Connection.open(address, Handshake(StreamKind.CONTROL), connectTimeoutMs)
    return try {
        withCancellationClosing(conn) {
            conn.writeFrame(MessageCodec.encode(ControlRequest.serializer(), ScreenshotRequest))
            val headerBytes = conn.readFrame() ?: throw EOFException("no control response from agent")
            val header = MessageCodec.decode(ControlResponse.serializer(), headerBytes) as? ScreenshotResponse
            if (header?.ok != true) null
            else conn.readFrame() ?: throw EOFException("screenshot header was ok but no image blob followed")
        }
    } finally {
        conn.close()
    }
}

/**
 * Push a single JAR: PUSH handshake -> manifest frame -> blob frames -> empty
 * terminator -> read [PushResult]. In v1 exactly one blob is sent.
 */
suspend fun pushJar(
    address: AgentAddress,
    jar: Path,
    mainClass: String,
    jvmArgs: List<String> = emptyList(),
    programArgs: List<String> = emptyList(),
    restart: Boolean = true,
    onProgress: PushProgress? = null,
    onNotice: ((String) -> Unit)? = null,
): PushResult {
    // Strip stale JAR signature files so a fat jar over a signed dependency runs instead of failing
    // the JVM's signature verification (change add-push-signature-stripping). Integrity is computed
    // over the transferred (cleaned) jar. No signatures -> original jar, no temp copy.
    val stripped = withContext(Dispatchers.IO) { runCatching { stripJarSignatures(jar) }.getOrNull() }
    val src = stripped?.jar ?: jar
    try {
        if (stripped != null) {
            onNotice?.invoke(
                "stripped ${stripped.removed.size} stale signature file(s): ${stripped.removed.joinToString(", ")}. " +
                    "Tip: strip them at build time for faster pushes — add to your jar/uber task: " +
                    "exclude(\"META-INF/*.SF\", \"META-INF/*.RSA\", \"META-INF/*.DSA\", \"META-INF/*.EC\")",
            )
        }
        val size = Files.size(src)
        val sha = sha256(src)
        val manifest = PushManifest(
            entries = listOf(BlobEntry(jar.fileName.toString(), sha, size)),
            mainClass = mainClass,
            jvmArgs = jvmArgs,
            programArgs = programArgs,
            restart = restart,
        )
        val conn = Connection.open(address, Handshake(StreamKind.PUSH))
        return try {
            withCancellationClosing(conn) {
                conn.writeFrame(MessageCodec.encode(manifest))
                var sent = 0L
                Files.newInputStream(src).use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        conn.writeFrame(buf.copyOf(n))
                        sent += n
                        onProgress?.invoke(sent, size)
                    }
                }
                conn.writeFrame(ByteArray(0)) // end-of-blob terminator
                val bytes = conn.readFrame() ?: throw EOFException("no push result from agent")
                MessageCodec.decode<PushResult>(bytes)
            }
        } finally {
            conn.close()
        }
    } finally {
        if (stripped != null) Files.deleteIfExists(stripped.jar)
    }
}

/**
 * Distribute a new agent build (app-image zip) to an agent for self-update:
 * AGENT_UPDATE handshake -> manifest frame -> zip blob frames -> empty terminator
 * -> read [PushResult].
 */
suspend fun sendAgentUpdate(
    address: AgentAddress,
    zip: Path,
    version: String,
    onProgress: PushProgress? = null,
): PushResult {
    val size = Files.size(zip)
    val sha = sha256(zip)
    val manifest = AgentUpdateManifest(version = version, sha256 = sha, size = size)
    val conn = Connection.open(address, Handshake(StreamKind.AGENT_UPDATE))
    return try {
        withCancellationClosing(conn) {
            conn.writeFrame(MessageCodec.encode(manifest))
            var sent = 0L
            Files.newInputStream(zip).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    conn.writeFrame(buf.copyOf(n))
                    sent += n
                    onProgress?.invoke(sent, size)
                }
            }
            conn.writeFrame(ByteArray(0))
            val bytes = conn.readFrame() ?: throw EOFException("no agent-update result from agent")
            MessageCodec.decode<PushResult>(bytes)
        }
    } finally {
        conn.close()
    }
}

/**
 * A reload push ready to send: the [batch] manifest plus the class bytes for its
 * ADDED/MODIFIED entries, keyed by classpath-relative path. REMOVED entries carry no bytes.
 */
data class ReloadPayload(
    val batch: ReloadBatch,
    val bytesByPath: Map<String, ByteArray>,
)

/**
 * Push a batch of changed classes to a live hot-reload app: RELOAD handshake ->
 * [ReloadBatch] frame -> per-entry class-blob frames (each terminated by an empty
 * frame, in [ReloadBatch.entries] order, ADDED/MODIFIED only) -> read [ReloadResult].
 */
suspend fun sendReload(
    address: AgentAddress,
    payload: ReloadPayload,
    onProgress: PushProgress? = null,
): ReloadResult {
    val total = payload.bytesByPath.values.sumOf { it.size.toLong() }
    val conn = Connection.open(address, Handshake(StreamKind.RELOAD))
    return try {
        withCancellationClosing(conn) {
            conn.writeFrame(MessageCodec.encode(payload.batch))
            var sent = 0L
            for (entry in payload.batch.entries) {
                if (entry.changeType == ReloadChangeType.REMOVED) continue
                val bytes = payload.bytesByPath[entry.relPath]
                    ?: throw IllegalArgumentException("reload payload missing bytes for ${entry.relPath}")
                var off = 0
                val chunk = 64 * 1024
                while (off < bytes.size) {
                    val n = minOf(chunk, bytes.size - off)
                    conn.writeFrame(bytes.copyOfRange(off, off + n))
                    off += n
                    sent += n
                    onProgress?.invoke(sent, total)
                }
                conn.writeFrame(ByteArray(0)) // end-of-blob terminator for this entry
            }
            val resp = conn.readFrame() ?: throw EOFException("no reload result from agent")
            MessageCodec.decode<ReloadResult>(resp)
        }
    } finally {
        conn.close()
    }
}

/**
 * Subscribe to an agent's log stream. The agent delivers retained history first,
 * then live events (design D12/D21). Consumer backpressure is applied by [send];
 * the agent drops for a slow subscriber rather than stalling the app.
 */
fun streamLogs(address: AgentAddress): Flow<LogEvent> = channelFlow {
    val producer = this
    val conn = Connection.open(address, Handshake(StreamKind.LOGS))
    try {
        withContext(Dispatchers.IO) {
            coroutineScope {
                // Close the socket on cancellation so the blocking readFrame() unblocks.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { conn.close() }
                    }
                }
                try {
                    while (true) {
                        val bytes = conn.readFrame() ?: break // agent closed the stream
                        producer.send(MessageCodec.decode(LogEvent.serializer(), bytes))
                    }
                } catch (_: Throwable) {
                    // socket closed on cancellation/teardown, or agent went away — end quietly
                } finally {
                    watcher.cancel()
                }
            }
        }
    } finally {
        conn.close()
    }
    // returning from the channelFlow block closes the channel, ending the collector
}

internal fun sha256(path: Path): String {
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
