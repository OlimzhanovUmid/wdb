package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.ControlRequest
import uz.disastrouspumpkin.wdb.protocol.ControlResponse
import uz.disastrouspumpkin.wdb.protocol.DebugSuspendRequest
import uz.disastrouspumpkin.wdb.protocol.ErrorCode
import uz.disastrouspumpkin.wdb.protocol.ErrorResponse
import uz.disastrouspumpkin.wdb.protocol.FrameCodec
import uz.disastrouspumpkin.wdb.protocol.Handshake
import uz.disastrouspumpkin.wdb.protocol.HandshakeAck
import uz.disastrouspumpkin.wdb.protocol.LogEvent
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import uz.disastrouspumpkin.wdb.protocol.OkResponse
import uz.disastrouspumpkin.wdb.protocol.PROTOCOL_VERSION
import uz.disastrouspumpkin.wdb.protocol.ProtocolError
import uz.disastrouspumpkin.wdb.protocol.BringToFrontRequest
import uz.disastrouspumpkin.wdb.protocol.HotRunRequest
import uz.disastrouspumpkin.wdb.protocol.PushManifest
import uz.disastrouspumpkin.wdb.protocol.PushResult
import uz.disastrouspumpkin.wdb.protocol.ReloadBatch
import uz.disastrouspumpkin.wdb.protocol.ReloadChangeType
import uz.disastrouspumpkin.wdb.protocol.ReloadOutcome
import uz.disastrouspumpkin.wdb.protocol.ReloadResult
import uz.disastrouspumpkin.wdb.protocol.RestartRequest
import uz.disastrouspumpkin.wdb.protocol.RollbackRequest
import uz.disastrouspumpkin.wdb.protocol.RunRequest
import uz.disastrouspumpkin.wdb.protocol.ScreenshotRequest
import uz.disastrouspumpkin.wdb.protocol.ScreenshotResponse
import uz.disastrouspumpkin.wdb.protocol.SemanticTreeRequest
import uz.disastrouspumpkin.wdb.protocol.SemanticTreeResponse
import uz.disastrouspumpkin.wdb.protocol.StatusRequest
import uz.disastrouspumpkin.wdb.protocol.StatusResponse
import uz.disastrouspumpkin.wdb.protocol.StopRequest
import uz.disastrouspumpkin.wdb.protocol.StreamKind
import uz.disastrouspumpkin.wdb.protocol.UiActionKind
import uz.disastrouspumpkin.wdb.protocol.UiActionRequest
import uz.disastrouspumpkin.wdb.protocol.UiActionResponse
import uz.disastrouspumpkin.wdb.protocol.isCompatible
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * The agent's single TCP port. Accepts one connection per stream and dispatches by
 * handshake kind (design D3). Mutating commands (run/stop/restart/rollback/push)
 * are serialized on [mutationLock]; status/logs/tunnel run concurrently.
 */
class AgentServer(
    private val config: AgentConfig,
    private val store: DeploymentStore,
    private val supervisor: Supervisor,
    private val hub: LogHub,
    private val selfUpdater: SelfUpdater? = null,
    private val hotReload: HotReloadCoordinator? = null,
) : Closeable {
    // SO_REUSEADDR so a fresh agent can rebind the port right after a self-update handoff,
    // even if the old socket is briefly in TIME_WAIT.
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(config.tcpPort))
    }
    private val mutationLock = ReentrantLock()
    @Volatile private var acceptThread: Thread? = null

    // Reload applies share the mutation lock (serialized against push/agent-update).
    private val reloadApplier = ReloadApplier(
        isHotRunning = supervisor::isHotRunning,
        hotDir = { supervisor.currentHotLaunch()?.hotDir },
        coordinator = hotReload,
        lock = mutationLock,
    )

    fun port(): Int = serverSocket.localPort

    fun start() {
        acceptThread = thread(isDaemon = true, name = "wdb-agent-accept") {
            try {
                while (!serverSocket.isClosed) {
                    val sock = serverSocket.accept()
                    thread(isDaemon = true, name = "wdb-conn") { handle(sock) }
                }
            } catch (_: Throwable) {
                // server closed
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }

    private fun handle(sock: Socket) {
        sock.use {
            sock.tcpNoDelay = true
            val din = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            val hsBytes = FrameCodec.readFrame(din) ?: return
            val hs = MessageCodec.decode<Handshake>(hsBytes)
            if (!isCompatible(PROTOCOL_VERSION, hs.protocolVersion)) {
                FrameCodec.writeFrame(
                    dout,
                    MessageCodec.encode(
                        HandshakeAck(ok = false, error = ProtocolError(ErrorCode.VERSION_MISMATCH, "agent speaks v$PROTOCOL_VERSION")),
                    ),
                )
                return
            }
            FrameCodec.writeFrame(dout, MessageCodec.encode(HandshakeAck(ok = true)))

            when (hs.kind) {
                StreamKind.CONTROL -> handleControl(din, dout)
                StreamKind.PUSH -> handlePush(din, dout)
                StreamKind.LOGS -> handleLogs(din, dout, sock)
                StreamKind.TUNNEL -> handleTunnel(hs.tunnelPort, din, dout, sock)
                StreamKind.AGENT_UPDATE -> handleAgentUpdate(din, dout)
                StreamKind.RELOAD -> handleReload(din, dout)
            }
        }
    }

    private fun handleControl(din: DataInputStream, dout: DataOutputStream) {
        val bytes = FrameCodec.readFrame(din) ?: return
        val request = MessageCodec.decode(ControlRequest.serializer(), bytes)
        // Screenshot returns the PNG as a raw blob frame after the header, not base64 in JSON
        // (change add-binary-screenshot-transport) — a full-HD screen exceeds a JSON+base64 frame.
        if (request is ScreenshotRequest) {
            handleScreenshot(dout)
            return
        }
        val response = dispatchControl(request)
        FrameCodec.writeFrame(dout, MessageCodec.encode(ControlResponse.serializer(), response))
    }

    /** Write the [ScreenshotResponse] header, then on success the raw PNG bytes as one blob frame. */
    private fun handleScreenshot(dout: DataOutputStream) {
        val png = if (hotReload != null && supervisor.isHotRunning()) hotReload.screenshot() else null
        val header = if (png != null) ScreenshotResponse(ok = true)
        else ScreenshotResponse(ok = false, error = "no screenshot (app not in hot mode / devtools unavailable)")
        FrameCodec.writeFrame(dout, MessageCodec.encode(ControlResponse.serializer(), header))
        if (png != null) FrameCodec.writeFrame(dout, png)
    }

    private fun dispatchControl(request: ControlRequest): ControlResponse = when (request) {
        StatusRequest -> StatusResponse(supervisor.status())
        RunRequest -> mutating { supervisor.launch(); OkResponse }
        StopRequest -> mutating { supervisor.stop(); OkResponse }
        RestartRequest -> mutating { supervisor.restart(); OkResponse }
        DebugSuspendRequest -> mutating { supervisor.restart(suspend = true); OkResponse }
        BringToFrontRequest -> {
            val pid = supervisor.runningPid()
            when {
                pid == null -> ErrorResponse(ProtocolError(ErrorCode.INTERNAL, "no app running"))
                uz.disastrouspumpkin.wdb.agent.win.BringToFront.bringToFront(pid) -> OkResponse
                else -> ErrorResponse(ProtocolError(ErrorCode.INTERNAL, "no window found for the app"))
            }
        }
        HotRunRequest -> mutating {
            val coord = hotReload
            if (coord == null) {
                ErrorResponse(ProtocolError(ErrorCode.INTERNAL, "hot reload not available (agent not installed for hot mode)"))
            } else {
                supervisor.launch(hot = coord.beginHotRun())
                OkResponse
            }
        }
        RollbackRequest -> mutating {
            val rolled = store.rollback()
            if (rolled == null) {
                ErrorResponse(ProtocolError(ErrorCode.NO_PREVIOUS_DEPLOYMENT, "no previous deployment"))
            } else {
                if (supervisor.appState() == AppState.RUNNING) supervisor.restart()
                OkResponse
            }
        }
        // Devtools (read/interact; not serialized on the mutation lock).
        // Screenshot is intercepted in handleControl (header + raw PNG blob frame); never dispatched here.
        ScreenshotRequest -> error("screenshot is handled via the blob path in handleControl")
        SemanticTreeRequest -> withHotCoordinator { coord ->
            coord.semanticTree()
                ?.let { SemanticTreeResponse(ok = true, tree = it) }
                ?: SemanticTreeResponse(ok = false, error = "no semantic tree (devtools unavailable?)")
        } ?: SemanticTreeResponse(ok = false, error = "app not in hot mode")
        is UiActionRequest -> withHotCoordinator { coord ->
            if (coord.uiAction(request.nodeId, request)) {
                UiActionResponse(ok = true)
            } else {
                UiActionResponse(ok = false, error = "action not applied")
            }
        } ?: UiActionResponse(ok = false, error = "app not in hot mode")
    }

    /** Run [block] with the hot coordinator only while a hot run is active; else null. */
    private inline fun withHotCoordinator(block: (HotReloadCoordinator) -> ControlResponse): ControlResponse? {
        val coord = hotReload ?: return null
        if (!supervisor.isHotRunning()) return null
        return block(coord)
    }

    private inline fun mutating(block: () -> ControlResponse): ControlResponse =
        mutationLock.withLock {
            try {
                block()
            } catch (e: NotDeployedException) {
                ErrorResponse(ProtocolError(ErrorCode.NOT_DEPLOYED, e.message ?: "not deployed"))
            }
        }

    private fun handlePush(din: DataInputStream, dout: DataOutputStream) {
        val manifest = MessageCodec.decode<PushManifest>(FrameCodec.readFrame(din) ?: return)
        config.paths.ensure()
        val tmpDir = config.dataDir.resolve("tmp").also { Files.createDirectories(it) }
        val tmp = Files.createTempFile(tmpDir, "push", ".jar")
        val result = try {
            Files.newOutputStream(tmp).use { out ->
                while (true) {
                    val frame = FrameCodec.readFrame(din) ?: break
                    if (frame.isEmpty()) break // end-of-blob terminator
                    out.write(frame)
                }
            }
            mutationLock.withLock {
                val dep = store.commit(manifest, tmp)
                val wasRunning = supervisor.appState() == AppState.RUNNING
                store.makeCurrent(dep.sha)
                // A new deployment invalidates any hot-reload deltas: they were compiled against
                // the OLD jar, and the hot dir sits ahead of the jar on the classpath, so stale
                // classes would shadow the new jar (and can poison a redeploy-fallback recovery).
                clearHotDir()
                val restarted = if (wasRunning && manifest.restart) {
                    supervisor.restart(); true
                } else {
                    false
                }
                PushResult(ok = true, deployedSha = dep.sha, restarted = restarted)
            }
        } catch (e: IntegrityException) {
            Files.deleteIfExists(tmp)
            PushResult(ok = false, error = ProtocolError(ErrorCode.INTEGRITY_FAILED, e.message ?: "integrity check failed"))
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            PushResult(ok = false, error = ProtocolError(ErrorCode.INTERNAL, e.message ?: e.toString()))
        }
        FrameCodec.writeFrame(dout, MessageCodec.encode(result))
    }

    private fun handleAgentUpdate(din: DataInputStream, dout: DataOutputStream) {
        val manifest = MessageCodec.decode<uz.disastrouspumpkin.wdb.protocol.AgentUpdateManifest>(FrameCodec.readFrame(din) ?: return)
        val updater = selfUpdater
        if (updater == null) {
            FrameCodec.writeFrame(
                dout,
                MessageCodec.encode(PushResult(ok = false, error = ProtocolError(ErrorCode.INTERNAL, "self-update not available (agent not installed)"))),
            )
            return
        }
        config.paths.ensure()
        val tmpDir = config.dataDir.resolve("tmp").also { Files.createDirectories(it) }
        val tmp = Files.createTempFile(tmpDir, "agent-update", ".zip")
        val result = try {
            Files.newOutputStream(tmp).use { out ->
                while (true) {
                    val frame = FrameCodec.readFrame(din) ?: break
                    if (frame.isEmpty()) break
                    out.write(frame)
                }
            }
            mutationLock.withLock { updater.apply(manifest, tmp) }
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            PushResult(ok = false, error = ProtocolError(ErrorCode.INTERNAL, e.message ?: e.toString()))
        }
        FrameCodec.writeFrame(dout, MessageCodec.encode(result))
        dout.flush()
        // Reply is on the wire; now hand off to the new version (production restarts + exits).
        if (result.ok) runCatching { updater.triggerRestart() }
    }

    /**
     * Apply a hot-reload batch to the live app (design D2, spec compose-hot-reload).
     * Always drains the class-blob frames first (so the wire stays framed), then: rejects
     * if no hot run is active or the batch fails integrity (app untouched), else writes the
     * bytes into the hot dir and asks the coordinator to redefine + recompose. Serialized on
     * [mutationLock] so two reloads can't interleave a redefine.
     */
    private fun handleReload(din: DataInputStream, dout: DataOutputStream) {
        val batch = MessageCodec.decode<ReloadBatch>(FrameCodec.readFrame(din) ?: return)
        // Drain one blob (empty-frame terminated) per ADDED/MODIFIED entry, in manifest order.
        val bytesByPath = LinkedHashMap<String, ByteArray>()
        for (entry in batch.entries) {
            if (entry.changeType == ReloadChangeType.REMOVED) continue
            val buf = ByteArrayOutputStream()
            while (true) {
                val frame = FrameCodec.readFrame(din) ?: break
                if (frame.isEmpty()) break
                buf.write(frame)
            }
            bytesByPath[entry.relPath] = buf.toByteArray()
        }

        val result = try {
            reloadApplier.apply(batch, bytesByPath)
        } catch (e: Throwable) {
            ReloadResult(ReloadOutcome.FAILED, e.message ?: e.toString())
        }
        FrameCodec.writeFrame(dout, MessageCodec.encode(result))
    }

    /** Delete the hot-classpath dir's contents so a new deployment starts with no stale deltas. */
    private fun clearHotDir() {
        val dir = config.paths.hotClasspathDir
        if (!Files.isDirectory(dir)) return
        runCatching {
            Files.walk(dir).use { walk ->
                walk.sorted(Comparator.reverseOrder())
                    .filter { it != dir }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun handleLogs(din: DataInputStream, dout: DataOutputStream, sock: Socket) {
        val (sub, history) = hub.subscribe()
        // A logs client never sends after the handshake, so a read returns -1 on its
        // FIN. Watch for that and close the socket, so the (possibly idle) write loop
        // below exits and unsubscribes instead of spinning forever (leak fix).
        val finWatcher = thread(isDaemon = true, name = "wdb-logs-fin") {
            try {
                while (din.read() >= 0) { /* unexpected client data — ignore */ }
            } catch (_: Throwable) {
                // socket closed
            }
            runCatching { sock.close() }
        }
        try {
            for (event in history) FrameCodec.writeFrame(dout, MessageCodec.encode(LogEvent.serializer(), event))
            while (!sock.isClosed) {
                val event = sub.poll(200) ?: continue
                FrameCodec.writeFrame(dout, MessageCodec.encode(LogEvent.serializer(), event))
            }
        } catch (_: Throwable) {
            // client disconnected — stop streaming; the app is unaffected
        } finally {
            hub.unsubscribe(sub)
            runCatching { sock.close() }
            finWatcher.interrupt()
        }
    }

    /**
     * Relay to a loopback port on this machine (design D19). The protocol has no
     * host field, so a tunnel can only ever reach 127.0.0.1 — non-loopback targets
     * are unrepresentable, hence refused by construction.
     */
    private fun handleTunnel(tunnelPort: Int?, din: DataInputStream, dout: DataOutputStream, sock: Socket) {
        if (tunnelPort == null) return
        val target = try {
            Socket(InetAddress.getLoopbackAddress(), tunnelPort)
        } catch (_: Throwable) {
            return
        }
        target.use {
            // Capture streams up front (see client Tunnel) before either copy closes a socket.
            val targetOut = target.getOutputStream()
            val targetIn = target.getInputStream()
            val a = thread(isDaemon = true) { copyThenClose(din, targetOut, sock, target) }
            copyThenClose(targetIn, dout, sock, target)
            a.join()
        }
    }

    private fun copyThenClose(from: java.io.InputStream, to: java.io.OutputStream, s1: Socket, s2: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { s1.close() }
            runCatching { s2.close() }
        }
    }
}
