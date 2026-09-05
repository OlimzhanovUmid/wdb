package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Production self-restart after an applied update (design D4/D5). The applier has already
 * switched `current-version` to the new version (a plain file write). It spawns a watchdog
 * from the PREVIOUS version's binary — a stable, known-good process — and exits so its lock
 * and ports are released. The watchdog relaunches (and rolls back if the new agent fails).
 */
fun productionRestart(base: Path) {
    val layout = AgentInstallLayout(base)
    val prev = layout.readMarker()?.previousVersion ?: layout.previousVersion()
    if (prev != null) {
        val watchdogExe = layout.versionDir(prev).resolve("wdb-agent.exe")
        val exists = Files.exists(watchdogExe)
        val spawn = if (exists) {
            runCatching {
                ProcessBuilder(watchdogExe.toString(), "--supervise-update", base.toString(), "60").start()
            }.fold({ "ok" }, { "err: ${it.message}" })
        } else "skipped (exe missing)"
        layout.log("restart: prev=$prev watchdog=$watchdogExe exists=$exists spawn=$spawn")
    } else {
        layout.log("restart: no previous version — cannot spawn watchdog")
    }
    exitProcess(0)
}

/**
 * Watchdog (runs from the previous, known-good binary). After the applier exits: relaunch
 * via the task (the launcher stub reads the now-new `current-version`). If the new agent
 * confirms health by clearing the marker before the deadline, commit; otherwise revert
 * `current-version` to the previous version and relaunch that.
 */
fun superviseUpdate(base: Path, deadlineSeconds: Long) {
    val layout = AgentInstallLayout(base)
    val marker = layout.readMarker()
    layout.log("supervise start marker=${if (marker == null) "absent" else "present(new=${marker.newVersion} prev=${marker.previousVersion})"}")
    if (marker == null) return

    Thread.sleep(2000) // let the applier fully exit and release the lock/ports
    relaunch(layout)

    val start = System.currentTimeMillis()
    val deadline = start + deadlineSeconds * 1000
    while (System.currentTimeMillis() < deadline) {
        if (layout.readMarker() == null) {
            layout.log("committed ${marker.newVersion} after ${(System.currentTimeMillis() - start) / 1000}s")
            return // healthy new agent committed the update
        }
        Thread.sleep(1000)
    }
    // New version never came back healthy -> roll back to the previous version.
    val reverted = layout.revertToPrevious()
    layout.clearMarker()
    layout.log("revert -> $reverted (new ${marker.newVersion} never cleared marker within ${deadlineSeconds}s)")
    relaunch(layout)
}

/**
 * Relaunch the agent for the current version. Spawns the versioned launcher stub directly
 * (`cmd /c launch.cmd`, which reads `current-version`) rather than `schtasks /run`: Task Scheduler's
 * single-instance policy makes `/run` a silent no-op while it still holds the task's prior instance as
 * running (the outgoing agent that just exited), which stalls self-update until the 60s revert. A direct
 * spawn always starts the process; the single-instance `agent.lock` serializes the handoff. Falls back
 * to `schtasks /run` only when the stub is absent (older installs). Started detached — the watchdog must
 * keep polling, not wait on the long-running agent.
 */
private fun relaunch(layout: AgentInstallLayout) {
    val stub = layout.launchCmd
    if (Files.exists(stub)) {
        val r = runCatching { ProcessBuilder("cmd", "/c", stub.toString()).start() }
            .fold({ "ok" }, { "err: ${it.message}" })
        layout.log("relaunch via launch.cmd = $r")
    } else {
        val r = runCatching { ProcessBuilder("schtasks", "/run", "/tn", TASK_NAME).start() }
            .fold({ "ok" }, { "err: ${it.message}" })
        layout.log("relaunch via schtasks (no launch.cmd) = $r")
    }
}

/**
 * Detect the versioned-install base from the running binary's path (…/agent/versions/<v>/…);
 * null in dev/manual runs (self-update stays disabled).
 */
fun detectInstallBase(): Path? {
    val cmd = ProcessHandle.current().info().command().orElse(null) ?: return null
    var dir: Path? = runCatching { Path.of(cmd).parent }.getOrNull()
    repeat(6) {
        val d = dir ?: return null
        val agent = d.resolve("agent")
        if (Files.exists(agent.resolve("current-version")) || Files.exists(agent.resolve("versions"))) return d
        dir = d.parent
    }
    return null
}
