package uz.disastrouspumpkin.wdb.mcp

import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.ClassDiff
import uz.disastrouspumpkin.wdb.client.WdbClient
import uz.disastrouspumpkin.wdb.client.reloadOrRedeploy
import uz.disastrouspumpkin.wdb.protocol.LogLine
import uz.disastrouspumpkin.wdb.protocol.UiActionKind
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.jar.JarFile

/**
 * wdb MCP server (change add-wdb-mcp): exposes the demo wall to an AI agent over MCP/stdio —
 * list machines, screenshot, semantic tree, UI actions, and lifecycle. Embeds [WdbClient].
 *
 * Tool bodies live in the `tool*` functions below so they are unit-testable against a
 * protocol-speaking fake (host injected) without a real MCP client; the [registerTools]
 * lambdas only parse arguments and delegate.
 */
fun main() = runBlocking {
    val client = WdbClient(this)
    val cache = MachineCache(discover = { client.discover() })
    val collectors = LogCollectors(this, client)
    val server = Server(
        Implementation(name = "wdb-mcp", version = "0.2.0"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = null),
                // subscribe = true so clients can observe the wdb://logs/{machine} resource (D5).
                resources = ServerCapabilities.Resources(subscribe = true, listChanged = false),
            ),
        ),
    )
    registerTools(server, client, cache, collectors)

    val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered()) {}
    val done = Job()
    val session = server.createSession(transport)
    session.onClose {
        collectors.cancelAll()
        done.complete()
    }
    done.join()
}

// --- result + schema helpers -------------------------------------------------

internal fun text(s: String, isError: Boolean = false): CallToolResult =
    CallToolResult(content = listOf<ContentBlock>(TextContent(text = s)), isError = isError)

private fun image(png: ByteArray): CallToolResult =
    CallToolResult(content = listOf<ContentBlock>(ImageContent(data = Base64.getEncoder().encodeToString(png), mimeType = "image/png")))

private fun objSchema(props: Map<String, String>, required: List<String>): ToolSchema {
    val properties = buildJsonObject {
        props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
    }
    // `type` defaults to "object"; leave `$schema`/`$defs` unset so the emitted input schema is
    // a plain draft-2020-12 object schema ({type, properties, required}). Setting `schema` here
    // would serialize as `"$schema": "object"`, which is not a valid schema URI.
    return ToolSchema(properties = properties, required = required)
}

private fun CallToolRequest.str(name: String): String? = params.arguments?.get(name)?.jsonPrimitive?.contentOrNull
private fun CallToolRequest.int(name: String): Int? = str(name)?.toIntOrNull()
private fun CallToolRequest.float(name: String): Float? = str(name)?.toFloatOrNull()

// --- tool bodies (testable seam; host is null in production, injected in tests) ----

internal suspend fun toolListMachines(cache: MachineCache): CallToolResult {
    val machines = cache.list()
    return if (machines.isEmpty()) text("no machines found")
    else text(machines.joinToString("\n") { "${it.name}  ${it.address.host}:${it.address.port}  ${it.appState?.name ?: "?"}" })
}

internal suspend fun toolScreenshot(client: WdbClient, machine: String, host: AgentAddress? = null): CallToolResult {
    val png = client.screenshot(machine, host)
    return if (png == null) text("devtools unavailable for $machine (app not in hot mode?)", isError = true) else image(png)
}

internal suspend fun toolSemanticTree(client: WdbClient, machine: String, host: AgentAddress? = null): CallToolResult =
    client.semanticTree(machine, host)?.let { text(it) } ?: text("devtools unavailable for $machine", isError = true)

internal suspend fun toolUiAction(
    client: WdbClient,
    machine: String,
    nodeId: Int,
    kind: UiActionKind,
    text: String = "",
    dx: Float = 0f,
    dy: Float = 0f,
    index: Int = 0,
    host: AgentAddress? = null,
): CallToolResult {
    val ok = client.uiAction(machine, nodeId, kind, text, dx, dy, index, host)
    return text(if (ok) "$kind applied to node $nodeId" else "action not applied", isError = !ok)
}

internal suspend fun toolLogs(client: WdbClient, machine: String, lines: Int, host: AgentAddress? = null): CallToolResult {
    val collected = ArrayList<String>()
    runCatching {
        withTimeoutOrNull(3000) {
            client.logs(machine, host).collect { ev ->
                if (ev is LogLine) {
                    collected += ev.text
                    if (collected.size > 5000) return@collect
                }
            }
        }
    }
    return text(collected.takeLast(lines).joinToString("\n").ifEmpty { "(no logs)" })
}

internal suspend fun toolStatus(client: WdbClient, machine: String, host: AgentAddress? = null): CallToolResult {
    val st = runCatching { client.status(machine, host) }.getOrNull()
        ?: return text("cannot reach $machine", isError = true)
    val jdwp = st.jdwpPort?.let { "$it${if (st.jdwpPortIsFallback) " (fallback)" else ""}" } ?: "-"
    return text(
        buildString {
            appendLine("name:      ${st.name}")
            appendLine("app:       ${st.appState}   desired: ${st.desiredState}   hot: ${st.hotMode}")
            appendLine("deployed:  ${st.deployedSha ?: "-"}   previous: ${st.previousSha ?: "-"}")
            appendLine("mainClass: ${st.mainClass ?: "-"}")
            appendLine("jdwpPort:  $jdwp")
            appendLine("uptime:    ${st.uptimeMillis ?: "-"} ms   restarts: ${st.restartCount}   lastExit: ${st.lastExitCode ?: "-"}")
            append("agent/rt:  ${st.agentVersion} / ${st.runtimeVersion}")
        },
    )
}

/** Read a jar's manifest `Main-Class`, or null. */
internal fun readMainClass(jar: Path): String? =
    runCatching { JarFile(jar.toFile()).use { it.manifest?.mainAttributes?.getValue("Main-Class") } }.getOrNull()

internal suspend fun toolDeploy(
    client: WdbClient,
    machine: String,
    jarPath: String,
    restart: Boolean = true,
    mainClass: String? = null,
    host: AgentAddress? = null,
): CallToolResult {
    val jar = Path.of(jarPath)
    if (!Files.isRegularFile(jar)) return text("not a file: $jarPath", isError = true)
    val main = mainClass?.takeIf { it.isNotBlank() } ?: readMainClass(jar)
    if (main.isNullOrBlank()) return text("no Main-Class in $jarPath (pass mainClass)", isError = true)
    val notes = StringBuilder()
    val r = runCatching { client.push(machine, jar, main, restart = restart, host = host, onNotice = { notes.appendLine(it) }) }
        .getOrElse { return text("$machine: deploy failed — ${it.message}", isError = true) }
    return if (r.ok) text("$notes$machine: deployed ${r.deployedSha?.take(12) ?: "(ok)"}")
    else text("$notes$machine: deploy failed — ${r.error?.message}", isError = true)
}

// --- registration ------------------------------------------------------------

private fun registerTools(server: Server, client: WdbClient, cache: MachineCache, collectors: LogCollectors) {
    val machineArg = mapOf("machine" to "string")

    server.addTool(Tool(name = "list_machines", description = "Discover wall machines (name, address, app state).", inputSchema = objSchema(emptyMap(), emptyList()))) { _ ->
        toolListMachines(cache)
    }

    server.addTool(Tool(name = "screenshot", description = "PNG screenshot of a hot machine's screen.", inputSchema = objSchema(machineArg, listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        toolScreenshot(client, m, host)
    }

    server.addTool(Tool(name = "semantic_tree", description = "Semantic tree (JSON) of a hot machine's UI.", inputSchema = objSchema(machineArg, listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        toolSemanticTree(client, m, host)
    }

    val actionSchema = objSchema(
        mapOf("machine" to "string", "nodeId" to "integer", "kind" to "string", "text" to "string", "dx" to "number", "dy" to "number", "index" to "integer"),
        listOf("machine", "nodeId", "kind"),
    )
    server.addTool(Tool(name = "ui_action", description = "Act on a semantic node: kind = click|long_click|set_text|scroll_by|scroll_to_index.", inputSchema = actionSchema)) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val nodeId = req.int("nodeId") ?: return@addTool text("nodeId required", isError = true)
        val kind = req.str("kind")?.let { runCatching { UiActionKind.valueOf(it.uppercase()) }.getOrNull() }
            ?: return@addTool text("kind must be one of ${UiActionKind.entries.joinToString { it.name.lowercase() }}", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        toolUiAction(client, m, nodeId, kind, req.str("text") ?: "", req.float("dx") ?: 0f, req.float("dy") ?: 0f, req.int("index") ?: 0, host)
    }

    server.addTool(Tool(name = "status", description = "Full status of a machine (app state, hot mode, jdwp, uptime, deployed sha, versions).", inputSchema = objSchema(machineArg, listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        toolStatus(client, m, host)
    }

    server.addTool(Tool(name = "run", description = "Run the current deployment on a machine.", inputSchema = objSchema(machineArg, listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        runCatching { client.run(m, host) }.fold({ text("$m: running") }, { text("$m: ${it.message}", isError = true) })
    }
    server.addTool(Tool(name = "hot_run", description = "Run in Compose hot-reload mode.", inputSchema = objSchema(machineArg, listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        runCatching { client.hotRun(m, host) }.fold({ text("$m: running (hot)") }, { text("$m: ${it.message}", isError = true) })
    }
    server.addTool(Tool(name = "stop", description = "Stop the app on a machine.", inputSchema = objSchema(machineArg, listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        runCatching { client.stop(m, host) }.fold({ text("$m: stopped") }, { text("$m: ${it.message}", isError = true) })
    }
    server.addTool(Tool(name = "bring_to_front", description = "Raise the running app's window to the foreground on a machine.", inputSchema = objSchema(machineArg, listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        runCatching { client.bringToFront(m, host) }.fold({ text("$m: brought to front") }, { text("$m: ${it.message}", isError = true) })
    }
    server.addTool(
        Tool(
            name = "reload",
            description = "Hot-reload compiled classes from a dev-side classes dir into a hot app.",
            inputSchema = objSchema(mapOf("machine" to "string", "classesDir" to "string"), listOf("machine", "classesDir")),
        ),
    ) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val dir = req.str("classesDir") ?: return@addTool text("classesDir required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        val path = Path.of(dir)
        if (!Files.isDirectory(path)) return@addTool text("not a directory: $dir", isError = true)
        val (payload, _) = ClassDiff.buildPayload(path, emptyMap())
        if (payload.batch.entries.isEmpty()) return@addTool text("no classes under $dir")
        val report = client.reloadOrRedeploy(m, payload, host = host, redeploy = null)
        text("$m: reload ${report::class.simpleName}")
    }

    server.addTool(
        Tool(
            name = "deploy",
            description = "Push an already-built jar to a machine and (by default) restart it. No Gradle build.",
            inputSchema = objSchema(
                mapOf("machine" to "string", "jarPath" to "string", "restart" to "boolean", "mainClass" to "string"),
                listOf("machine", "jarPath"),
            ),
        ),
    ) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val jarPath = req.str("jarPath") ?: return@addTool text("jarPath required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        toolDeploy(client, m, jarPath, restart = req.bool("restart") ?: true, mainClass = req.str("mainClass"), host = host)
    }

    server.addTool(Tool(name = "logs", description = "Recent log lines from a machine.", inputSchema = objSchema(mapOf("machine" to "string", "lines" to "integer"), listOf("machine")))) { req ->
        val m = req.str("machine") ?: return@addTool text("machine required", isError = true)
        val host = cache.resolve(m) ?: return@addTool text("machine not found: $m", isError = true)
        toolLogs(client, m, req.int("lines") ?: 200, host)
    }

    // Streaming logs resource (D5): reading wdb://logs/{machine} starts a per-(session, machine)
    // collector that tails the machine's logs and pushes resource-updated notifications so the client
    // re-reads a fresh tail. SDK 0.13 has no subscribe hook, so the collector starts on first read.
    server.addResourceTemplate(
        "wdb://logs/{machine}",
        "Machine logs",
        "Streaming tail of a machine's app logs; re-read on resource-updated for newer lines.",
        "text/plain",
    ) { _, vars ->
        val conn = this
        val m = vars["machine"]
        if (m.isNullOrBlank()) {
            ReadResourceResult(listOf<ResourceContents>(TextResourceContents("machine required", "wdb://logs/", "text/plain")))
        } else {
            val host = cache.resolve(m)
            if (host == null) {
                ReadResourceResult(listOf<ResourceContents>(TextResourceContents("machine not found: $m", "wdb://logs/$m", "text/plain")))
            } else {
                val tail = collectors.read(conn.sessionId, m, host) { uri ->
                    conn.sendResourceUpdated(ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri)))
                }
                ReadResourceResult(listOf<ResourceContents>(TextResourceContents(tail, "wdb://logs/$m", "text/plain")))
            }
        }
    }
}

private fun CallToolRequest.bool(name: String): Boolean? = str(name)?.toBooleanStrictOrNull()
