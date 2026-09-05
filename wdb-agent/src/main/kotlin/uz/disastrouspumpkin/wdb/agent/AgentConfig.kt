package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Path

// Generated from the `wdbAgentVersion` gradle property by the buildConfig plugin.
const val AGENT_VERSION: String = BuildConfig.AGENT_VERSION

/** Default TCP port the agent's connection server listens on. */
const val DEFAULT_AGENT_PORT: Int = 7420

/** Default fixed JDWP debug port (the conventional JVM remote-debug port). */
const val DEFAULT_JDWP_PORT: Int = 5005

/**
 * Everything the agent needs to run: identity, ports, the data directory, and
 * the `java` used to launch apps (the bundled JBR in production, the running
 * JVM's java in dev/tests).
 */
data class AgentConfig(
    val machineName: String,
    val machineId: String,
    val dataDir: Path,
    val tcpPort: Int = DEFAULT_AGENT_PORT,
    val udpPort: Int = uz.disastrouspumpkin.wdb.client.DEFAULT_DISCOVERY_PORT,
    /** Preferred fixed JDWP port; the supervisor falls back to an ephemeral port if it is busy. */
    val jdwpPort: Int = DEFAULT_JDWP_PORT,
    val javaExecutable: Path = defaultJavaExecutable(),
    val agentVersion: String = AGENT_VERSION,
    val runtimeVersion: String = System.getProperty("java.version"),
    /** Versioned-install base dir; non-null enables self-update. Null in dev/manual runs. */
    val installBase: Path? = null,
    /** Path to the bundled CHR `-javaagent` jar; non-null enables hot-reload mode. */
    val hotReloadAgentJar: Path? = null,
    /** Bundled CHR runtime jars prepended to the hot app's classpath (devtools handlers); empty disables devtools. */
    val devtoolsRuntimeJars: List<Path> = emptyList(),
) {
    val paths: AgentPaths = AgentPaths(dataDir)
}

fun defaultJavaExecutable(): Path {
    val home = System.getProperty("java.home")
    val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    return Path.of(home, "bin", exe)
}

fun defaultDataDir(): Path {
    val base = System.getenv("LOCALAPPDATA")?.let { Path.of(it, "wdb") }
        ?: Path.of(System.getProperty("user.home"), ".wdb")
    return base
}
