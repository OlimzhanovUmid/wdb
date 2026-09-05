package uz.disastrouspumpkin.wdb.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One node of the CHR semantic tree (change add-plugin-devtools). */
data class SemanticNode(
    val id: Int?,
    val text: String?,
    val role: String?,
    /** `Modifier.testTag(...)` — the app-assigned stable name, when set. */
    val testTag: String? = null,
    /** Accessibility label (`contentDescription`), when set. */
    val contentDescription: String? = null,
    val actions: List<String>,
    val x: Int, val y: Int, val width: Int, val height: Int,
    val children: List<SemanticNode>,
) {
    /** True if the node exposes a click action, i.e. a tap on it does something. */
    val clickable: Boolean get() = "onClick" in actions
    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < x + width && py < y + height
}

/**
 * Parse the CHR semantic-tree JSON into a [SemanticNode], or null on bad JSON. CHR emits a single
 * owner as a JSON object, but **multiple owners as a JSON array** — a Compose dialog opens a second
 * owner (its own semantics root). Multiple owners are wrapped under a synthetic root so the whole
 * tree (dialog included) is visible and hit-testable; the dialog, being the last/topmost owner, is
 * checked first by [clickableNodeAt] (change: dialog/multi-window support).
 */
fun parseSemanticTree(treeJson: String): SemanticNode? {
    val el = runCatching { Json.parseToJsonElement(treeJson) }.getOrNull() ?: return null
    return runCatching {
        when (el) {
            is JsonObject -> toNode(el)
            is JsonArray -> {
                val owners = el.map { toNode(it.jsonObject) }
                when (owners.size) {
                    0 -> null
                    1 -> owners[0]
                    else -> SemanticNode(
                        id = null, text = null, role = null, actions = emptyList(),
                        x = 0, y = 0,
                        width = owners.maxOf { it.x + it.width }, height = owners.maxOf { it.y + it.height },
                        children = owners,
                    )
                }
            }
            else -> null
        }
    }.getOrNull()
}

private fun toNode(node: JsonObject): SemanticNode {
    val b = node["bounds"] as? JsonObject
    val children = (node["children"])?.jsonArray?.map { toNode(it.jsonObject) } ?: emptyList()
    val actions = (node["actions"])?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    return SemanticNode(
        id = node["id"]?.jsonPrimitive?.intOrNull,
        text = node["text"]?.jsonPrimitive?.contentOrNull,
        role = node["role"]?.jsonPrimitive?.contentOrNull,
        testTag = node["testTag"]?.jsonPrimitive?.contentOrNull,
        contentDescription = node["contentDescription"]?.jsonPrimitive?.contentOrNull,
        actions = actions,
        x = b?.get("x")?.jsonPrimitive?.int ?: 0,
        y = b?.get("y")?.jsonPrimitive?.int ?: 0,
        width = b?.get("width")?.jsonPrimitive?.int ?: 0,
        height = b?.get("height")?.jsonPrimitive?.int ?: 0,
        children = children,
    )
}

/**
 * Id of the deepest **clickable** node whose bounds contain (x,y) in semantic (tree) coordinates,
 * or null if none / bad JSON. Only click-actionable nodes are targets — full-screen overlay/container
 * nodes that merely cover the point (and have no `onClick`) are skipped so a tap reaches the button
 * underneath, not the overlay on top.
 */
fun hitTestSemanticTree(treeJson: String, x: Int, y: Int): Int? =
    parseSemanticTree(treeJson)?.let { clickableNodeAt(it, x, y) }

/** Deepest clickable node (in [node]'s subtree) whose bounds contain (x,y), or null. */
fun clickableNodeAt(node: SemanticNode, x: Int, y: Int): Int? {
    if (!node.contains(x, y)) return null
    for (i in node.children.indices.reversed()) {
        clickableNodeAt(node.children[i], x, y)?.let { return it }
    }
    return if (node.clickable) node.id else null
}
