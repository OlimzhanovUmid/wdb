package uz.disastrouspumpkin.wdb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Control channel: request / response
// ---------------------------------------------------------------------------

@Serializable
sealed interface ControlRequest

@Serializable @SerialName("run")
data object RunRequest : ControlRequest

@Serializable @SerialName("stop")
data object StopRequest : ControlRequest

@Serializable @SerialName("restart")
data object RestartRequest : ControlRequest

@Serializable @SerialName("rollback")
data object RollbackRequest : ControlRequest

@Serializable @SerialName("status")
data object StatusRequest : ControlRequest

/** Relaunch the app with JDWP `suspend=y` so startup can be debugged (design D5). */
@Serializable @SerialName("debug-suspend")
data object DebugSuspendRequest : ControlRequest

/** Launch the current deployment in Compose hot-reload mode (design D3). */
@Serializable @SerialName("hot-run")
data object HotRunRequest : ControlRequest

/** Raise the running app's window to the foreground on the machine (change add-bring-to-front). */
@Serializable @SerialName("bring-to-front")
data object BringToFrontRequest : ControlRequest

// --- Devtools (change add-plugin-devtools): inspect / interact with a hot app's UI ---

/** An action on a semantic node of the hot app's UI. */
@Serializable
enum class UiActionKind { CLICK, LONG_CLICK, SET_TEXT, SCROLL_BY, SCROLL_TO_INDEX }

/** Request a PNG screenshot of the hot app's current screen. */
@Serializable @SerialName("screenshot")
data object ScreenshotRequest : ControlRequest

/** Request the hot app's semantic tree (JSON) for hit-testing and inspection. */
@Serializable @SerialName("semantic-tree")
data object SemanticTreeRequest : ControlRequest

/**
 * Dispatch [kind] to the semantic node [nodeId] in the hot app. [text] is used only for SET_TEXT;
 * [dx]/[dy] only for SCROLL_BY; [index] only for SCROLL_TO_INDEX.
 */
@Serializable @SerialName("ui-action")
data class UiActionRequest(
    val nodeId: Int,
    val kind: UiActionKind,
    val text: String = "",
    val dx: Float = 0f,
    val dy: Float = 0f,
    val index: Int = 0,
) : ControlRequest

@Serializable
sealed interface ControlResponse

@Serializable @SerialName("ok")
data object OkResponse : ControlResponse

@Serializable @SerialName("status")
data class StatusResponse(val status: MachineStatus) : ControlResponse

@Serializable @SerialName("error")
data class ErrorResponse(val error: ProtocolError) : ControlResponse

/**
 * Screenshot reply header. On [ok] the agent sends the raw PNG bytes as one trailing blob frame after
 * this header (change add-binary-screenshot-transport); no image data rides in the JSON.
 */
@Serializable @SerialName("screenshot")
data class ScreenshotResponse(val ok: Boolean, val format: String = "png", val error: String? = null) : ControlResponse

/** The hot app's semantic tree as JSON (per-node id / bounds / text / children). */
@Serializable @SerialName("semantic-tree")
data class SemanticTreeResponse(val ok: Boolean, val tree: String = "", val error: String? = null) : ControlResponse

/** Whether a [UiActionRequest] was applied. */
@Serializable @SerialName("ui-action")
data class UiActionResponse(val ok: Boolean, val error: String? = null) : ControlResponse

/** Full machine status payload (design D23). */
@Serializable
data class MachineStatus(
    val machineId: String,
    val name: String,
    val appState: AppState,
    val desiredState: DesiredState,
    val uptimeMillis: Long? = null,
    val restartCount: Int = 0,
    val lastExitCode: Int? = null,
    val deployedSha: String? = null,
    val previousSha: String? = null,
    val mainClass: String? = null,
    val jdwpPort: Int? = null,
    /** True when the configured fixed JDWP port was busy and an ephemeral port is in use. */
    val jdwpPortIsFallback: Boolean = false,
    /** True when the current run was launched in Compose hot-reload mode. */
    val hotMode: Boolean = false,
    val agentVersion: String,
    val runtimeVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

// ---------------------------------------------------------------------------
// Push channel: manifest -> blob frames -> result
// ---------------------------------------------------------------------------

@Serializable
data class BlobEntry(val name: String, val sha256: String, val size: Long)

/**
 * Sent as the first frame of a [StreamKind.PUSH] connection. In v1 [entries]
 * holds exactly one JAR, but the shape allows a later per-artifact blob cache
 * (design D8) without a protocol break. Blob bytes follow as raw frames in
 * [entries] order, each terminated by a zero-length frame.
 */
@Serializable
data class PushManifest(
    val entries: List<BlobEntry>,
    val mainClass: String,
    val jvmArgs: List<String> = emptyList(),
    val programArgs: List<String> = emptyList(),
    /** false = stage only (`--no-restart`): don't restart a running app. */
    val restart: Boolean = true,
)

/**
 * First frame of a [StreamKind.AGENT_UPDATE] connection: describes the agent
 * app-image zip that follows as raw blob frames (terminated by a zero-length frame).
 */
@Serializable
data class AgentUpdateManifest(
    val version: String,
    val sha256: String,
    val size: Long,
)

/** Final frame of a push connection. */
@Serializable
data class PushResult(
    val ok: Boolean,
    val deployedSha: String? = null,
    val restarted: Boolean = false,
    val error: ProtocolError? = null,
)

// ---------------------------------------------------------------------------
// Reload channel: batch manifest -> class-blob frames -> result
// ---------------------------------------------------------------------------

/** Whether a class in a [ReloadBatch] was added, modified, or removed since the last push. */
@Serializable
enum class ReloadChangeType { ADDED, MODIFIED, REMOVED }

/**
 * One changed class in a [ReloadBatch], identified by its classpath-relative path
 * (e.g. `com/example/App.class`). [sha256]/[size] describe the bytes that follow for
 * ADDED/MODIFIED entries; a REMOVED entry carries no bytes ([size] = 0, [sha256] empty).
 */
@Serializable
data class ReloadEntry(
    val relPath: String,
    val changeType: ReloadChangeType,
    val sha256: String = "",
    val size: Long = 0,
)

/**
 * First frame of a [StreamKind.RELOAD] connection: the batch of changed classes to
 * apply to a live hot-reload app. Bytes for ADDED/MODIFIED entries follow as raw blob
 * frames in [entries] order (each terminated by a zero-length frame); REMOVED entries
 * carry no bytes. [batchSha256] covers the whole batch so the agent can verify integrity
 * before applying it to the running process.
 */
@Serializable
data class ReloadBatch(
    val entries: List<ReloadEntry>,
    val batchSha256: String,
)

/**
 * Result of a [ReloadBatch].
 * - [APPLIED]: classes were live-redefined; the app kept running.
 * - [REJECTED]: not applicable — the app is not in hot mode, or the batch failed its
 *   integrity check. The app is untouched and the client should NOT redeploy (fix and retry).
 * - [FAILED]: a hot-apply was attempted but the change could not be redefined (beyond DCEVM's
 *   limits, or it left the app broken). The client falls back to a full redeploy + restart.
 */
@Serializable
enum class ReloadOutcome { APPLIED, REJECTED, FAILED }

/**
 * Final frame of a reload connection. On [ReloadOutcome.FAILED], [reason] explains why
 * (integrity, not in hot mode, or a change beyond the runtime's redefinition limits) so
 * the client can fall back to a full redeploy + restart of that machine.
 */
@Serializable
data class ReloadResult(
    val outcome: ReloadOutcome,
    val reason: String? = null,
)

// ---------------------------------------------------------------------------
// Logs channel: stream of events
// ---------------------------------------------------------------------------

@Serializable
sealed interface LogEvent

@Serializable @SerialName("line")
data class LogLine(
    val stream: LogStream,
    val timestampMillis: Long,
    val text: String,
) : LogEvent

/** Emitted when a new app run begins, delimiting one run's output from the next. */
@Serializable @SerialName("run-boundary")
data class RunBoundary(
    val runId: Long,
    val timestampMillis: Long,
) : LogEvent

/** Emitted to a slow subscriber after the agent drops backed-up output (design D21). */
@Serializable @SerialName("dropped")
data class DroppedMarker(
    val count: Long,
    val timestampMillis: Long,
) : LogEvent

// ---------------------------------------------------------------------------
// Discovery datagrams (UDP; a single JSON datagram each, not framed)
// ---------------------------------------------------------------------------

@Serializable
data class DiscoveryQuery(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val nonce: String,
)

@Serializable
data class DiscoveryAnswer(
    val machineId: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val appState: AppState,
    val desiredState: DesiredState,
    /** Echo of [DiscoveryQuery.nonce], so a client can match replies to its query. */
    val nonce: String,
)
