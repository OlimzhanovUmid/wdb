package uz.disastrouspumpkin.wdb.protocol

/**
 * Wire protocol version. Only the MAJOR number participates in compatibility:
 * two peers interoperate iff their majors match (see [negotiate]). Minor-level
 * additions must stay backward compatible (new optional JSON fields with
 * defaults, tolerated via `ignoreUnknownKeys` in [MessageCodec]).
 */
const val PROTOCOL_VERSION: Int = 1

/** Thrown/represented when two peers cannot agree on a protocol major version. */
class ProtocolVersionException(
    val localVersion: Int,
    val remoteVersion: Int,
) : Exception("incompatible protocol version: local=$localVersion remote=$remoteVersion")

/**
 * Decide whether [remoteVersion] is compatible with [localVersion].
 * v1 uses a single integer as the major; any difference is incompatible.
 */
fun isCompatible(localVersion: Int, remoteVersion: Int): Boolean = localVersion == remoteVersion

/** Verify compatibility, throwing [ProtocolVersionException] on mismatch. */
fun negotiate(localVersion: Int, remoteVersion: Int) {
    if (!isCompatible(localVersion, remoteVersion)) {
        throw ProtocolVersionException(localVersion, remoteVersion)
    }
}
