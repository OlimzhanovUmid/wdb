package uz.disastrouspumpkin.wdb.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The role a single TCP connection plays. Declared in the [Handshake]. */
@Serializable
enum class StreamKind {
    /** Request/response control commands (JSON frames). */
    CONTROL,

    /** Binary app upload: JSON manifest frame, raw blob frames, JSON result frame. */
    PUSH,

    /** One-way stream of [LogEvent]s (JSON frames). */
    LOGS,

    /** Raw byte relay to a loopback port on the agent; unframed after the ack. */
    TUNNEL,

    /** Agent self-update upload: JSON manifest frame, zip blob frames, JSON result frame. */
    AGENT_UPDATE,

    /**
     * Compose hot-reload push: JSON [ReloadBatch] frame, raw class-blob frames for
     * ADDED/MODIFIED entries, JSON [ReloadResult] frame. Applied to a live hot-mode app.
     */
    RELOAD,
}

/** Observed lifecycle state of the supervised app. */
@Serializable
enum class AppState { STOPPED, RUNNING, CRASHED }

/** Operator-intended state, persisted so it survives reboots (design D15). */
@Serializable
enum class DesiredState { RUNNING, STOPPED }

/** Which standard stream a log line came from. */
@Serializable
enum class LogStream { STDOUT, STDERR }

/** Machine-readable error codes carried in [ProtocolError]. */
@Serializable
enum class ErrorCode {
    VERSION_MISMATCH,
    NOT_DEPLOYED,
    NO_PREVIOUS_DEPLOYMENT,
    TUNNEL_TARGET_FORBIDDEN,
    INTEGRITY_FAILED,
    /** A [StreamKind.RELOAD] push arrived for an app not running in hot-reload mode. */
    HOT_RELOAD_REQUIRED,
    BAD_REQUEST,
    INTERNAL,
}

@Serializable
data class ProtocolError(val code: ErrorCode, val message: String)

/**
 * First frame on every connection. [tunnelPort] is required when [kind] is
 * [StreamKind.TUNNEL] and names the loopback port on the agent to relay to.
 */
@Serializable
data class Handshake(
    val kind: StreamKind,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val tunnelPort: Int? = null,
)

/** Server's reply to a [Handshake]. On failure [error] explains why. */
@Serializable
data class HandshakeAck(
    val ok: Boolean,
    val serverVersion: Int = PROTOCOL_VERSION,
    val error: ProtocolError? = null,
)

/**
 * JSON encoding for all control/logs/push-manifest frames and discovery datagrams.
 * `ignoreUnknownKeys` gives forward compatibility within a major version;
 * `classDiscriminator = "type"` names the subtype for sealed hierarchies.
 */
object MessageCodec {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(value).encodeToByteArray()

    inline fun <reified T> decode(bytes: ByteArray): T =
        json.decodeFromString(bytes.decodeToString())

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        json.decodeFromString(serializer, bytes.decodeToString())
}
