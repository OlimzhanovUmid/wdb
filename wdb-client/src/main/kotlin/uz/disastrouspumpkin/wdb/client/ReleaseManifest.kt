package uz.disastrouspumpkin.wdb.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One component's entry in the release manifest (`latest.json`, published by the release pipeline).
 * `sha256` + `size` mirror the agent's [dev-side integrity check] so a downloaded asset can be
 * verified before use. Parsing lives here (not in the plugin) so the IntelliJ plugin — which does
 * the HTTP itself — needs no kotlinx-serialization on its own classpath.
 */
@Serializable
data class ComponentRelease(
    val version: String,
    val asset: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

private val releaseJson = Json { ignoreUnknownKeys = true }

/** Parse `latest.json`: a map of component name (`agent`/`cli`/`mcp`/`plugin`) -> [ComponentRelease]. */
fun parseReleaseManifest(json: String): Map<String, ComponentRelease> =
    releaseJson.decodeFromString(json)

/**
 * True iff [candidate] is a strictly newer dotted-numeric version than [current] — so we never
 * offer a downgrade or churn an equal version. An unparseable/unknown [current] (e.g. `"?"`) or
 * [candidate] returns false (don't auto-offer). Pre-release/build suffixes are ignored.
 */
fun isNewerVersion(current: String, candidate: String): Boolean {
    val cur = parseVersion(current) ?: return false
    val cand = parseVersion(candidate) ?: return false
    return compareVersions(cand, cur) > 0
}

private fun parseVersion(v: String): List<Int>? {
    val core = v.trim().substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    val out = ArrayList<Int>()
    for (part in core.split('.')) out.add(part.toIntOrNull() ?: return null)
    return out
}

private fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
