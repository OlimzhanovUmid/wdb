package uz.disastrouspumpkin.wdb.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.ClassDiff
import uz.disastrouspumpkin.wdb.client.ClassSnapshot
import uz.disastrouspumpkin.wdb.client.Machine
import uz.disastrouspumpkin.wdb.client.ReloadReport
import uz.disastrouspumpkin.wdb.client.WdbClient
import uz.disastrouspumpkin.wdb.client.reloadOrRedeploy
import uz.disastrouspumpkin.wdb.protocol.LogLine
import uz.disastrouspumpkin.wdb.protocol.LogStream
import uz.disastrouspumpkin.wdb.protocol.RunBoundary
import uz.disastrouspumpkin.wdb.protocol.DroppedMarker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.jar.JarFile

const val DEFAULT_AGENT_PORT = 7420

fun main(args: Array<String>) = Wdb().subcommands(
    Devices(), Status(), Run(), Stop(), Restart(), Rollback(), Push(), Logs(), Debug(), AgentUpdate(), Reload(), Screenshot(), SemanticTree(), BringToFront(),
).main(args)

class Wdb : CliktCommand(name = "wdb") {
    override fun run() = Unit
}

/** `--host host[:port]` shared by machine-targeting commands. */
private fun CliktCommand.hostOption() =
    option("--host", help = "Agent address host[:port], bypassing discovery").convertHost()

private fun com.github.ajalt.clikt.parameters.options.RawOption.convertHost() =
    convert { raw ->
        val parts = raw.split(":")
        AgentAddress(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_AGENT_PORT)
    }

private fun <T> withClient(block: suspend (WdbClient) -> T): T = runBlocking {
    block(WdbClient(this))
}

class Devices : CliktCommand(name = "devices", help = "List reachable demo-wall machines") {
    override fun run() = withClient { client ->
        val machines = client.discover()
        if (machines.isEmpty()) {
            echo("no machines found")
        } else {
            echo("%-16s %-22s %-10s %s".format("NAME", "ADDRESS", "APP", "ID"))
            machines.sortedBy { it.name }.forEach { m ->
                echo("%-16s %-22s %-10s %s".format(m.name, "${m.address.host}:${m.address.port}", m.appState ?: "-", m.id))
            }
        }
    }
}

class Status : CliktCommand(name = "status", help = "Show a machine's status") {
    private val target by argument("machine")
    private val host by hostOption()
    override fun run() = withClient { client ->
        val s = client.status(target, host)
        echo("name:        ${s.name}")
        echo("app:         ${s.appState}   desired: ${s.desiredState}   hot: ${s.hotMode}")
        echo("deployed:    ${s.deployedSha ?: "-"}   previous: ${s.previousSha ?: "-"}")
        echo("mainClass:   ${s.mainClass ?: "-"}")
        echo("jdwpPort:    ${s.jdwpPort ?: "-"}")
        echo("restarts:    ${s.restartCount}   lastExit: ${s.lastExitCode ?: "-"}")
        echo("uptime(ms):  ${s.uptimeMillis ?: "-"}")
        echo("agent/rt:    ${s.agentVersion} / ${s.runtimeVersion}")
    }
}

class Run : CliktCommand(name = "run", help = "Launch the app on a machine") {
    private val target by argument("machine")
    private val hot by option("--hot", help = "Launch in Compose hot-reload mode").flag()
    private val host by hostOption()
    override fun run() = withClient {
        if (hot) { it.hotRun(target, host); echo("running $target (hot-reload mode)") }
        else { it.run(target, host); echo("running $target") }
    }
}

class Stop : CliktCommand(name = "stop", help = "Stop the app on a machine") {
    private val target by argument("machine")
    private val host by hostOption()
    override fun run() = withClient { it.stop(target, host); echo("stopped $target") }
}

class Restart : CliktCommand(name = "restart", help = "Restart the app on a machine") {
    private val target by argument("machine")
    private val host by hostOption()
    override fun run() = withClient { it.restart(target, host); echo("restarted $target") }
}

class Rollback : CliktCommand(name = "rollback", help = "Roll back to the previous deployment") {
    private val target by argument("machine")
    private val host by hostOption()
    override fun run() = withClient { it.rollback(target, host); echo("rolled back $target") }
}

class Push : CliktCommand(name = "push", help = "Deploy an app JAR to one or all machines") {
    private val jar by argument("jar").path(mustExist = true, canBeDir = false)
    private val target by argument("machine").optional()
    private val all by option("--all", help = "Push to every discovered machine").flag()
    private val noRestart by option("--no-restart", help = "Stage only; don't restart a running app").flag()
    private val mainClass by option("--main-class", help = "Override the JAR's Main-Class")
    private val jvmArgs by option("--jvm-arg", help = "JVM arg (repeatable)").multiple()
    private val programArgs by option("--program-arg", help = "Program arg (repeatable)").multiple()
    private val host by hostOption()

    override fun run() = withClient { client ->
        val main = mainClass ?: readMainClass(jar) ?: error("no Main-Class in JAR; pass --main-class")
        val targets: List<Pair<String, AgentAddress?>> = when {
            all -> client.discover().map { it.name to it.address }
            target != null -> listOf(target!! to host)
            else -> error("specify a machine or --all")
        }
        if (targets.isEmpty()) error("no machines to push to")
        for ((name, addr) in targets) {
            val result = runCatching {
                client.push(name, jar, main, jvmArgs, programArgs, restart = !noRestart, host = addr, onNotice = { echo("$name: $it") })
            }
            result.fold(
                onSuccess = { r ->
                    if (r.ok) {
                        echo("$name: ok sha=${r.deployedSha?.take(12)} restarted=${r.restarted}")
                    } else {
                        echo("$name: FAILED ${r.error?.code} ${r.error?.message}")
                    }
                },
                onFailure = { echo("$name: FAILED ${it.message}") },
            )
        }
    }
}

class Screenshot : CliktCommand(name = "screenshot", help = "Save a hot machine's screen as a PNG") {
    private val target by argument("machine")
    private val out by option("--out", help = "Output PNG path (default screenshot-<machine>.png)").default("")
    private val host by hostOption()
    override fun run() = withClient { client ->
        val png = client.screenshot(target, host)
        if (png == null) {
            echo("$target: no screenshot (app not in hot mode / devtools unavailable)")
        } else {
            val path = out.ifBlank { "screenshot-$target.png" }
            java.io.File(path).writeBytes(png)
            echo("$target: wrote ${png.size} bytes -> $path")
        }
    }
}

class BringToFront : CliktCommand(name = "bring-to-front", help = "Raise the running app's window to the foreground") {
    private val target by argument("machine")
    private val host by hostOption()
    override fun run() = withClient { client ->
        runCatching { client.bringToFront(target, host) }
            .fold({ echo("$target: brought to front") }, { echo("$target: ${it.message}") })
    }
}

class SemanticTree : CliktCommand(name = "semantic-tree", help = "Dump a hot machine's semantic tree (JSON)") {
    private val target by argument("machine")
    private val out by option("--out", help = "Write JSON to a file instead of stdout").default("")
    private val host by hostOption()
    override fun run() = withClient { client ->
        val tree = client.semanticTree(target, host)
        if (tree == null) {
            echo("$target: no semantic tree (app not in hot mode / devtools unavailable)")
        } else if (out.isNotBlank()) {
            java.io.File(out).writeText(tree)
            echo("$target: wrote ${tree.length} chars -> $out")
        } else {
            echo(tree)
        }
    }
}

class Logs : CliktCommand(name = "logs", help = "Stream a machine's app logs") {
    private val target by argument("machine")
    private val host by hostOption()
    override fun run() = withClient { client ->
        client.logs(target, host).collect { event ->
            when (event) {
                is LogLine -> echo("${if (event.stream == LogStream.STDERR) "E" else " "} ${event.text}")
                is RunBoundary -> echo("---- run ${event.runId} ----")
                is DroppedMarker -> echo("---- dropped ${event.count} lines ----")
            }
        }
    }
}

class Debug : CliktCommand(name = "debug", help = "Open a JDWP tunnel for a Remote JVM Debug attach") {
    private val target by argument("machine")
    private val suspend by option("--suspend", help = "Relaunch suspended until the debugger attaches").flag()
    private val localPort by option("--local-port", help = "Local port to bind").int().default(0)
    private val host by hostOption()

    override fun run() = withClient { client ->
        if (suspend) {
            client.debugSuspend(target, host)
            echo("relaunched $target suspended; attach to continue startup")
        }
        val status = client.status(target, host)
        val jdwp = status.jdwpPort ?: error("$target has no JDWP port (is the app running?)")
        val tunnel = client.openTunnel(target, jdwp, localPort, host)
        echo("JDWP tunnel: localhost:${tunnel.localPort}  ->  $target loopback:$jdwp")
        echo("IntelliJ/Android Studio: Run > Edit Configurations > Remote JVM Debug")
        echo("  host=localhost  port=${tunnel.localPort}")
        echo("Press Ctrl+C to close the tunnel.")
        awaitCancellation()
    }
}

class AgentUpdate : CliktCommand(name = "agent-update", help = "Distribute a new agent build (app-image zip) to machines") {
    private val zip by argument("agent-zip").path(mustExist = true, canBeDir = false)
    private val target by argument("machine").optional()
    private val all by option("--all", help = "Update every discovered machine").flag()
    private val version by option("--version", help = "Version label for the new build (default: zip file name)")
    private val host by hostOption()

    override fun run() = withClient { client ->
        val ver = version ?: zip.fileName.toString().removeSuffix(".zip")
        val targets: List<Pair<String, AgentAddress?>> = when {
            all -> client.discover().map { it.name to it.address }
            target != null -> listOf(target!! to host)
            else -> error("specify a machine or --all")
        }
        if (targets.isEmpty()) error("no machines to update")
        for ((name, addr) in targets) {
            runCatching { client.agentUpdate(name, zip, ver, host = addr) }.fold(
                onSuccess = { r ->
                    if (r.ok) echo("$name: updating to $ver (agent will restart)")
                    else echo("$name: FAILED ${r.error?.code} ${r.error?.message}")
                },
                onFailure = { echo("$name: FAILED ${it.message}") },
            )
        }
    }
}

class Reload : CliktCommand(
    name = "reload",
    help = "Hot-reload changed classes into a running hot-mode app (one or all machines)",
) {
    private val classesDir by argument("classes-dir").path(mustExist = true, canBeFile = false)
    private val target by argument("machine").optional()
    private val all by option("--all", help = "Reload every discovered machine").flag()
    private val watch by option("--watch", help = "Watch the classes dir and push each change").flag()
    private val fallbackJar by option("--fallback-jar", help = "On a failed reload, redeploy this JAR")
        .path(mustExist = true, canBeDir = false)
    private val mainClass by option("--main-class", help = "Main-Class for the redeploy fallback JAR")
    private val host by hostOption()

    override fun run() = withClient { client ->
        val targets: List<Pair<String, AgentAddress?>> = when {
            all -> client.discover().map { it.name to it.address }
            target != null -> listOf(target!! to host)
            else -> error("specify a machine or --all")
        }
        if (targets.isEmpty()) error("no machines to reload")
        // Per-target snapshot baselines so we push only the delta since each target's last push.
        val baselines = HashMap<String, ClassSnapshot>()

        suspend fun pushRound() {
            for ((name, addr) in targets) {
                val (payload, snapshot) = ClassDiff.buildPayload(classesDir, baselines[name] ?: emptyMap())
                if (payload.batch.entries.isEmpty()) continue // nothing changed for this target
                val redeploy: (suspend () -> uz.disastrouspumpkin.wdb.protocol.PushResult)? = fallbackJar?.let { jar ->
                    val main = mainClass ?: readMainClass(jar) ?: error("no Main-Class in fallback JAR; pass --main-class")
                    ({ client.push(name, jar, main, restart = true, host = addr) })
                }
                val report = runCatching { client.reloadOrRedeploy(name, payload, addr, redeploy) }
                report.fold(
                    onSuccess = { r ->
                        echo(renderReport(r))
                        // Advance the baseline only when the classes actually reached the target.
                        if (r !is ReloadReport.Failed) baselines[name] = snapshot
                    },
                    onFailure = { echo("$name: FAILED ${it.message}") },
                )
            }
        }

        fun signature() = ClassDiff.snapshot(classesDir).entries.joinToString { "${it.key}=${it.value}" }

        pushRound()
        if (watch) {
            echo("watching $classesDir — Ctrl+C to stop")
            var last = signature()
            while (true) {
                Thread.sleep(500)
                val sig = signature()
                if (sig != last) {
                    last = sig
                    pushRound()
                }
            }
        }
    }

    private fun renderReport(r: ReloadReport): String = when (r) {
        is ReloadReport.Applied -> "${r.target}: reloaded ${r.classCount} class(es)"
        is ReloadReport.Rejected -> "${r.target}: rejected (${r.reason})"
        is ReloadReport.Redeployed -> "${r.target}: reload failed -> redeployed (ok=${r.push.ok}, restarted=${r.push.restarted})"
        is ReloadReport.Failed -> "${r.target}: FAILED (${r.reason}); rebuild + wdb push to recover"
    }
}

private fun readMainClass(jar: Path): String? =
    runCatching { JarFile(jar.toFile()).use { it.manifest?.mainAttributes?.getValue("Main-Class") } }.getOrNull()
