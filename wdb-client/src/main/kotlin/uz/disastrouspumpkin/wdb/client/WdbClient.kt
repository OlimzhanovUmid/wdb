package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.ControlRequest
import uz.disastrouspumpkin.wdb.protocol.ControlResponse
import uz.disastrouspumpkin.wdb.protocol.DebugSuspendRequest
import uz.disastrouspumpkin.wdb.protocol.ErrorResponse
import uz.disastrouspumpkin.wdb.protocol.BringToFrontRequest
import uz.disastrouspumpkin.wdb.protocol.HotRunRequest
import uz.disastrouspumpkin.wdb.protocol.LogEvent
import uz.disastrouspumpkin.wdb.protocol.MachineStatus
import uz.disastrouspumpkin.wdb.protocol.OkResponse
import uz.disastrouspumpkin.wdb.protocol.PushResult
import uz.disastrouspumpkin.wdb.protocol.ReloadResult
import uz.disastrouspumpkin.wdb.protocol.RestartRequest
import uz.disastrouspumpkin.wdb.protocol.RollbackRequest
import uz.disastrouspumpkin.wdb.protocol.SemanticTreeRequest
import uz.disastrouspumpkin.wdb.protocol.SemanticTreeResponse
import uz.disastrouspumpkin.wdb.protocol.UiActionKind
import uz.disastrouspumpkin.wdb.protocol.UiActionRequest
import uz.disastrouspumpkin.wdb.protocol.UiActionResponse
import uz.disastrouspumpkin.wdb.protocol.RunRequest
import uz.disastrouspumpkin.wdb.protocol.StatusRequest
import uz.disastrouspumpkin.wdb.protocol.StatusResponse
import uz.disastrouspumpkin.wdb.protocol.StopRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/** Raised when the agent answers a control request with an error. */
class AgentErrorException(val response: ErrorResponse) :
    Exception("agent error: ${response.error.code} ${response.error.message}")

/**
 * The single client surface used by both the CLI and a future IntelliJ plugin
 * (design D6/D10). All operations resolve a target by id/name (or an explicit
 * host) via [MachineResolver], then talk to that agent over one connection per stream.
 */
class WdbClient(
    private val scope: CoroutineScope,
    private val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    private val cache: LastSeenCache = LastSeenCache.default(),
) {
    private val resolver = MachineResolver(cache) { discover() }

    /** Broadcast-query discovery; results are cached for later direct connects. */
    suspend fun discover(windowMs: Long = 1500): List<Machine> {
        val machines = uz.disastrouspumpkin.wdb.client.discover(discoveryPort, windowMs)
        machines.forEach(cache::put)
        return machines
    }

    suspend fun status(target: String, host: AgentAddress? = null): MachineStatus =
        control(target, host, StatusRequest).let {
            (it as? StatusResponse)?.status ?: throw unexpected(it)
        }

    suspend fun run(target: String, host: AgentAddress? = null) = expectOk(target, host, RunRequest)
    /** Launch the current deployment in Compose hot-reload mode. */
    suspend fun hotRun(target: String, host: AgentAddress? = null) = expectOk(target, host, HotRunRequest)
    suspend fun bringToFront(target: String, host: AgentAddress? = null) = expectOk(target, host, BringToFrontRequest)
    suspend fun stop(target: String, host: AgentAddress? = null) = expectOk(target, host, StopRequest)
    suspend fun restart(target: String, host: AgentAddress? = null) = expectOk(target, host, RestartRequest)
    suspend fun rollback(target: String, host: AgentAddress? = null) = expectOk(target, host, RollbackRequest)
    suspend fun debugSuspend(target: String, host: AgentAddress? = null) = expectOk(target, host, DebugSuspendRequest)

    /**
     * Devtools: PNG bytes of the hot app's screen, or null if unavailable. The agent sends the raw
     * PNG as a blob frame after the header (change add-binary-screenshot-transport).
     */
    suspend fun screenshot(target: String, host: AgentAddress? = null): ByteArray? =
        resolver.withResolved(target, host) { addr -> screenshotControl(addr) }

    /** Devtools: the hot app's semantic tree JSON, or null if unavailable. */
    suspend fun semanticTree(target: String, host: AgentAddress? = null): String? {
        val r = control(target, host, SemanticTreeRequest) as? SemanticTreeResponse ?: return null
        return if (r.ok) r.tree else null
    }

    /** Devtools: dispatch [kind] to semantic node [nodeId] (text/dx/dy/index per kind); true if applied. */
    suspend fun uiAction(
        target: String,
        nodeId: Int,
        kind: UiActionKind,
        text: String = "",
        dx: Float = 0f,
        dy: Float = 0f,
        index: Int = 0,
        host: AgentAddress? = null,
    ): Boolean {
        val r = control(target, host, UiActionRequest(nodeId, kind, text, dx, dy, index)) as? UiActionResponse ?: return false
        return r.ok
    }

    suspend fun push(
        target: String,
        jar: Path,
        mainClass: String,
        jvmArgs: List<String> = emptyList(),
        programArgs: List<String> = emptyList(),
        restart: Boolean = true,
        host: AgentAddress? = null,
        onProgress: PushProgress? = null,
        onNotice: ((String) -> Unit)? = null,
    ): PushResult = resolver.withResolved(target, host) { addr ->
        pushJar(addr, jar, mainClass, jvmArgs, programArgs, restart, onProgress, onNotice)
    }

    /** Distribute a new agent build (app-image zip) to a machine for self-update. */
    suspend fun agentUpdate(
        target: String,
        zip: Path,
        version: String,
        host: AgentAddress? = null,
        onProgress: PushProgress? = null,
    ): PushResult = resolver.withResolved(target, host) { addr ->
        sendAgentUpdate(addr, zip, version, onProgress)
    }

    /** Push a batch of changed classes to a live hot-reload app on a machine. */
    suspend fun reload(
        target: String,
        payload: ReloadPayload,
        host: AgentAddress? = null,
        onProgress: PushProgress? = null,
    ): ReloadResult = resolver.withResolved(target, host) { addr ->
        sendReload(addr, payload, onProgress)
    }

    /** Live log flow for a machine (history first, then live — see agent side). */
    suspend fun logs(target: String, host: AgentAddress? = null): Flow<LogEvent> =
        resolver.withResolved(target, host) { addr -> streamLogs(addr) }

    /** Open a debug/port forward to a loopback port on the machine. */
    suspend fun openTunnel(
        target: String,
        remoteLoopbackPort: Int,
        localPort: Int = 0,
        host: AgentAddress? = null,
    ): Tunnel = resolver.withResolved(target, host) { addr ->
        openTunnel(scope, addr, remoteLoopbackPort, localPort)
    }

    private suspend fun control(target: String, host: AgentAddress?, request: ControlRequest): ControlResponse =
        resolver.withResolved(target, host) { addr -> sendControl(addr, request) }

    private suspend fun expectOk(target: String, host: AgentAddress?, request: ControlRequest) {
        when (val r = control(target, host, request)) {
            is OkResponse -> Unit
            is ErrorResponse -> throw AgentErrorException(r)
            else -> throw unexpected(r)
        }
    }

    private fun unexpected(r: ControlResponse): Exception =
        (r as? ErrorResponse)?.let { AgentErrorException(it) }
            ?: IllegalStateException("unexpected response: $r")
}
