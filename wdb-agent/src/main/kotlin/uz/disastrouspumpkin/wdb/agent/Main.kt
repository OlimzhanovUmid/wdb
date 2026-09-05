package uz.disastrouspumpkin.wdb.agent

import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "run" -> runAgent(args)
        "install" -> {
            val name = flag(args, "--name") ?: hostname()
            val jdwpPort = flag(args, "--jdwp-port")?.toIntOrNull()
            InstallManager().install(name, jdwpPort).forEach(::println)
            println("installed as '$name'")
        }
        "uninstall" -> InstallManager().uninstall().forEach(::println)
        "--supervise-update" -> {
            // wdb-agent --supervise-update <base> <deadlineSeconds>
            superviseUpdate(java.nio.file.Path.of(args[1]), args[2].toLong())
        }
        else -> printUsage()
    }
}

private fun runAgent(args: Array<String>) {
    val dataDir = flag(args, "--data-dir")?.let { java.nio.file.Path.of(it) } ?: defaultDataDir()
    Files.createDirectories(dataDir)

    // Single-instance guard: hold an exclusive lock on a file in the data dir for the
    // process lifetime, so a second agent for the same machine refuses to start (D23).
    // Retry briefly so that during a self-update handoff the new agent WAITS for the old
    // one to exit and release the lock, instead of losing a race and dying.
    val lockChannel = FileChannel.open(
        dataDir.resolve("agent.lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
    )
    var lock = lockChannel.tryLock()
    val lockDeadline = System.currentTimeMillis() + 15_000
    while (lock == null && System.currentTimeMillis() < lockDeadline) {
        Thread.sleep(300)
        lock = runCatching { lockChannel.tryLock() }.getOrNull()
    }
    if (lock == null) {
        System.err.println("another wdb-agent is already running for $dataDir")
        exitProcess(1)
    }

    val paths = AgentPaths(dataDir)
    val machineId = loadOrCreateMachineId(paths)
    val name = flag(args, "--name") ?: readPersistedName(paths) ?: hostname()
    persistName(paths, name)
    val port = flag(args, "--port")?.toIntOrNull() ?: DEFAULT_AGENT_PORT
    val jdwpPort = flag(args, "--jdwp-port")?.toIntOrNull() ?: readPersistedJdwpPort(paths) ?: DEFAULT_JDWP_PORT
    persistJdwpPort(paths, jdwpPort)

    val config = AgentConfig(
        machineName = name, machineId = machineId, dataDir = dataDir,
        tcpPort = port, jdwpPort = jdwpPort, installBase = detectInstallBase(),
        hotReloadAgentJar = detectHotReloadAgentJar(),
        devtoolsRuntimeJars = detectDevtoolsRuntimeJars(),
    )
    val runtime = AgentRuntime(config)

    val stop = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        runtime.close()
        stop.countDown()
    })
    runtime.start()
    println("wdb-agent '$name' (id=$machineId) listening tcp=${runtime.port()} udp=${config.udpPort}")
    // Healthy start: clear any pending-update marker so the watchdog commits the update.
    config.installBase?.let {
        val layout = AgentInstallLayout(it)
        layout.log("boot ver=${BuildConfig.AGENT_VERSION} installBase=$it tcp=${runtime.port()}")
        val hadMarker = layout.readMarker() != null
        layout.clearMarker()
        layout.log(if (hadMarker) "marker cleared (update committed)" else "no marker (normal start)")
    }
    stop.await()
}

private fun readPersistedName(paths: AgentPaths) =
    paths.dataDir.resolve("machine-name").let { if (Files.exists(it)) Files.readString(it).trim().ifEmpty { null } else null }

private fun persistName(paths: AgentPaths, name: String) {
    runCatching { Files.writeString(paths.dataDir.resolve("machine-name"), name) }
}

private fun flag(args: Array<String>, name: String): String? {
    val idx = args.indexOf(name)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}

private fun hostname(): String =
    runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: System.getenv("COMPUTERNAME") ?: "wdb-machine"

private fun printUsage() {
    println(
        """
        wdb-agent — Windows Debug Bridge agent

        Usage:
          wdb-agent run [--name <machine>] [--port <tcp>] [--jdwp-port <n>] [--data-dir <dir>]
          wdb-agent install [--name <machine>] [--jdwp-port <n>]   (elevated)
          wdb-agent uninstall                       (elevated)
        """.trimIndent(),
    )
}
