package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.agent.win.DisplayAwake
import uz.disastrouspumpkin.wdb.agent.win.JobObject
import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.DesiredState
import uz.disastrouspumpkin.wdb.protocol.LogStream
import uz.disastrouspumpkin.wdb.protocol.MachineStatus
import java.io.BufferedReader
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class NotDeployedException : Exception("no deployment is current")

private class RunningApp(
    val process: Process,
    val pid: Long,
    val jdwpPort: Int,
    val jdwpIsFallback: Boolean,
    val runId: Long,
    val startedAt: Long,
    val hot: Boolean,
    @Volatile var stopRequested: Boolean = false,
)

/**
 * Parameters for a Compose hot-reload launch (design D3): the loopback port the agent's
 * orchestration server listens on, the hot-classpath dir that receives pushed class deltas,
 * and the CHR `-javaagent` jar bundled with the agent.
 */
data class HotLaunch(
    val orchestrationPort: Int,
    val hotDir: java.nio.file.Path,
    val agentJar: java.nio.file.Path,
)

/**
 * Owns the app process lifecycle (design D15/D16/D17/D20, spec process-supervision):
 * launches the current deployment in the interactive session with JDWP on a
 * loopback port, keeps it alive with bounded-backoff auto-restart, binds its
 * lifetime to a Job Object, keeps the display awake, and stops it gracefully.
 */
class Supervisor(
    private val config: AgentConfig,
    private val store: DeploymentStore,
    private val state: AgentState,
    private val hub: LogHub,
) {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val job: JobObject? = if (isWindows) runCatching { JobObject() }.getOrNull() else null

    private val lock = ReentrantLock()
    private var running: RunningApp? = null
    /** Non-null while the current/desired run is a hot-reload launch; reused across auto-restarts. */
    private var hotLaunch: HotLaunch? = null
    private var restartCount = 0
    private var lastExitCode: Int? = null
    private var crashed = false
    private val crashTimes = ArrayDeque<Long>()

    companion object {
        const val STORM_WINDOW_MS = 10_000L
        const val STORM_THRESHOLD = 5
        const val GRACEFUL_TIMEOUT_MS = 5_000L
        private const val MAX_BACKOFF_MS = 8_000L
    }

    /**
     * Launch the current deployment. [suspend] enables JDWP suspend-on-start.
     * [hot] non-null launches in Compose hot-reload mode (design D3), remembered
     * across auto-restarts until the next non-hot launch.
     */
    fun launch(suspend: Boolean = false, hot: HotLaunch? = null) {
        lock.withLock {
            state.setDesired(DesiredState.RUNNING)
            crashed = false
            restartCount = 0
            hotLaunch = hot
            if (running != null) return
            val dep = store.current() ?: throw NotDeployedException()
            startProcess(dep, suspend, hot)
        }
    }

    /** On agent startup: relaunch iff desired state is running and something is deployed (D15). */
    fun applyDesiredOnStartup() {
        lock.withLock {
            if (state.desiredState() == DesiredState.RUNNING && running == null && store.current() != null) {
                crashed = false
                startProcess(store.current()!!, suspend = false, hot = hotLaunch)
            }
        }
    }

    fun stop() {
        val app: RunningApp?
        lock.withLock {
            state.setDesired(DesiredState.STOPPED)
            crashed = false
            app = running
            app?.stopRequested = true
        }
        app?.let { gracefulKill(it) }
    }

    /** Stop the running app (if any) and launch the current deployment. */
    fun restart(suspend: Boolean = false) {
        val old: RunningApp?
        lock.withLock {
            old = running
            old?.stopRequested = true
        }
        old?.let { gracefulKill(it) }
        lock.withLock {
            state.setDesired(DesiredState.RUNNING)
            crashed = false
            restartCount = 0
            // the watcher for `old` sees running !== old and no-ops; safe to start fresh
            if (running != null && running !== old) return
            running = null
            val dep = store.current() ?: throw NotDeployedException()
            startProcess(dep, suspend, hotLaunch)
        }
    }

    /** True when an app is currently running in Compose hot-reload mode. */
    fun isHotRunning(): Boolean = lock.withLock { running?.hot == true }

    /** The active hot-launch descriptor (hot dir + orchestration port) while a hot app runs, else null. */
    fun currentHotLaunch(): HotLaunch? = lock.withLock { if (running?.hot == true) hotLaunch else null }

    fun appState(): AppState = lock.withLock {
        when {
            crashed -> AppState.CRASHED
            running != null -> AppState.RUNNING
            else -> AppState.STOPPED
        }
    }

    /** PID of the currently running app, or null if none (change add-bring-to-front). */
    fun runningPid(): Long? = lock.withLock { running?.pid }

    fun status(): MachineStatus = lock.withLock {
        val app = running
        val cur = store.current()
        MachineStatus(
            machineId = config.machineId,
            name = config.machineName,
            appState = when {
                crashed -> AppState.CRASHED
                app != null -> AppState.RUNNING
                else -> AppState.STOPPED
            },
            desiredState = state.desiredState(),
            uptimeMillis = app?.let { System.currentTimeMillis() - it.startedAt },
            restartCount = restartCount,
            lastExitCode = lastExitCode,
            deployedSha = cur?.sha,
            previousSha = store.previousSha(),
            mainClass = cur?.meta?.mainClass,
            jdwpPort = app?.jdwpPort,
            jdwpPortIsFallback = app?.jdwpIsFallback ?: false,
            hotMode = app?.hot ?: false,
            agentVersion = config.agentVersion,
            runtimeVersion = config.runtimeVersion,
        )
    }

    fun shutdown() {
        val app: RunningApp?
        lock.withLock { app = running; app?.stopRequested = true }
        app?.let { gracefulKill(it) }
        job?.close()
    }

    // --- internals (all process starts happen under `lock`) ---

    private fun startProcess(dep: Deployment, suspend: Boolean, hot: HotLaunch? = null) {
        val (jdwpPort, jdwpIsFallback) = chooseJdwpPort()
        val cmd = buildCommand(dep, jdwpPort, suspend, hot)
        val pb = ProcessBuilder(cmd).directory(dep.dir.toFile())
        pb.environment()["WDB_MACHINE_NAME"] = config.machineName
        pb.environment()["WDB_MACHINE_ID"] = config.machineId
        val process = pb.start()
        val pid = process.pid()
        job?.let { runCatching { it.assign(pid) } }
        DisplayAwake.keepAwake()
        val runId = hub.beginRun()
        val app = RunningApp(process, pid, jdwpPort, jdwpIsFallback, runId, System.currentTimeMillis(), hot = hot != null)
        running = app
        pumpStream(process.inputStream, LogStream.STDOUT, app)
        pumpStream(process.errorStream, LogStream.STDERR, app)
        startWatcher(app)
    }

    private fun buildCommand(dep: Deployment, jdwpPort: Int, suspend: Boolean, hot: HotLaunch? = null): List<String> =
        buildLaunchCommand(config.javaExecutable.toString(), dep, jdwpPort, suspend, hot, config.devtoolsRuntimeJars)

    private fun pumpStream(stream: InputStream, which: LogStream, app: RunningApp) {
        thread(isDaemon = true, name = "log-$which-${app.pid}") {
            BufferedReader(stream.reader(StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when (which) {
                        LogStream.STDOUT -> hub.stdout(line)
                        LogStream.STDERR -> hub.stderr(line)
                    }
                }
            }
        }
    }

    private fun startWatcher(app: RunningApp) {
        thread(isDaemon = true, name = "watch-${app.pid}") {
            val exit = app.process.waitFor()
            var scheduleRestart = false
            lock.withLock {
                if (running !== app) return@withLock // superseded by a newer launch
                lastExitCode = exit
                running = null
                DisplayAwake.release()
                if (app.stopRequested || state.desiredState() != DesiredState.RUNNING) return@withLock
                val t = System.currentTimeMillis()
                crashTimes.addLast(t)
                while (crashTimes.isNotEmpty() && t - crashTimes.first() > STORM_WINDOW_MS) crashTimes.removeFirst()
                if (crashTimes.size > STORM_THRESHOLD) {
                    crashed = true
                } else {
                    restartCount++
                    scheduleRestart = true
                }
            }
            if (scheduleRestart) {
                Thread.sleep(backoffMs(restartCount))
                lock.withLock {
                    if (!crashed && running == null && state.desiredState() == DesiredState.RUNNING) {
                        store.current()?.let { startProcess(it, suspend = false, hot = hotLaunch) }
                    }
                }
            }
        }
    }

    private fun backoffMs(attempt: Int): Long =
        minOf(MAX_BACKOFF_MS, 200L * (1L shl minOf(attempt, 5)))

    private fun gracefulKill(app: RunningApp) {
        // taskkill without /F posts WM_CLOSE so a windowed app runs its close handlers.
        if (isWindows) {
            runCatching {
                ProcessBuilder("taskkill", "/PID", app.pid.toString())
                    .redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS)
            }
        } else {
            app.process.destroy()
        }
        if (!app.process.waitFor(GRACEFUL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            app.process.destroyForcibly()
            app.process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    fun freeLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /**
     * Prefer the configured fixed JDWP port so a tunnel/IDE config survives restarts;
     * if it is busy, fall back to an ephemeral port and flag it. Returns (port, isFallback).
     */
    private fun chooseJdwpPort(): Pair<Int, Boolean> =
        if (isLoopbackPortFree(config.jdwpPort)) config.jdwpPort to false else freeLoopbackPort() to true

    private fun isLoopbackPortFree(port: Int): Boolean =
        runCatching { ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close() }.isSuccess
}

/**
 * Build the app launch command line. Normal runs use the `-jar` form (unchanged from v1).
 * A hot run ([hot] non-null) adds the CHR `-javaagent`, `-XX:+AllowEnhancedClassRedefinition`,
 * the `compose.reload.*` system properties, and switches to the `-cp "<hotDir>;<jar>" <mainClass>`
 * form so the hot-classpath dir is prepended and added classes resolve (design D3/D4).
 * Extracted as a pure function so the launch line is unit-testable without spawning a process.
 */
internal fun buildLaunchCommand(
    javaExe: String,
    dep: Deployment,
    jdwpPort: Int,
    suspend: Boolean,
    hot: HotLaunch?,
    devtoolsJars: List<java.nio.file.Path> = emptyList(),
): List<String> {
    val suspendFlag = if (suspend) "y" else "n"
    val sep = java.io.File.pathSeparator
    return buildList {
        add(javaExe)
        addAll(dep.meta.jvmArgs)
        add("-Dstdout.encoding=UTF-8")
        add("-Dstderr.encoding=UTF-8")
        add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$suspendFlag,address=127.0.0.1:$jdwpPort")
        if (hot != null) {
            add("-javaagent:${hot.agentJar}")
            add("-XX:+AllowEnhancedClassRedefinition")
            add("-Dcompose.reload.orchestration.port=${hot.orchestrationPort}")
            add("-Dcompose.reload.hotApplicationClasspath=${hot.hotDir}")
            add("-Dcompose.reload.isHotReloadActive=true")
            add("-cp")
            // devtools runtime jars first, then the hot-classpath dir, then the app jar.
            add((devtoolsJars.map { it.toString() } + hot.hotDir.toString() + dep.jar.toString()).joinToString(sep))
            add(dep.meta.mainClass)
        } else {
            add("-jar")
            add(dep.jar.toString())
        }
        addAll(dep.meta.programArgs)
    }
}
