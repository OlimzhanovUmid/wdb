package uz.disastrouspumpkin.wdb.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Pure edits to a Claude Code config (`~/.claude.json`) as a JSON string. Lives in wdb-client
 * because it owns kotlinx-serialization for the plugin (which does the file IO). Used by the
 * plugin's one-click MCP install (change add-plugin-mcp-install).
 */
private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/**
 * Upsert a user-scope stdio MCP server `mcpServers.<name>` = `{type:stdio, command, args:[], env:{}}`
 * into [configJson], preserving every other key. Creates a minimal object when [configJson] is
 * null/blank. Throws when the existing content is not a JSON object — the caller should fall back
 * (copy-to-clipboard) rather than overwrite an unexpected file.
 */
fun upsertUserMcpServer(configJson: String?, name: String, command: String): String {
    val root: JsonObject = if (configJson.isNullOrBlank()) {
        JsonObject(emptyMap())
    } else {
        Json.parseToJsonElement(configJson) as? JsonObject
            ?: throw IllegalArgumentException("~/.claude.json is not a JSON object")
    }
    val servers = ((root["mcpServers"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()).apply {
        put(name, buildJsonObject {
            put("type", "stdio")
            put("command", command)
            put("args", JsonArray(emptyList()))
            put("env", JsonObject(emptyMap()))
        })
    }
    val newRoot = buildJsonObject {
        root.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
        put("mcpServers", JsonObject(servers))
    }
    return prettyJson.encodeToString(JsonObject.serializer(), newRoot)
}

/** The `command` registered for MCP server [name] in [configJson], or null if absent/unparseable. */
fun mcpServerCommand(configJson: String?, name: String): String? = runCatching {
    val root = Json.parseToJsonElement(configJson ?: return null) as? JsonObject ?: return null
    val server = (root["mcpServers"] as? JsonObject)?.get(name) as? JsonObject ?: return null
    (server["command"] as? JsonPrimitive)?.contentOrNull
}.getOrNull()
