package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * On-disk layout of the agent's data directory:
 *
 *   <dataDir>/machine-id            persisted UUID (stable across reboots)
 *   <dataDir>/state.json            desired state, restart count, last exit code
 *   <dataDir>/deployments/<sha>/    one directory per deployed JAR (+ meta.json)
 *   <dataDir>/pointers.json         {current, previous} shas, swapped atomically
 */
class AgentPaths(val dataDir: Path) {
    val machineIdFile: Path get() = dataDir.resolve("machine-id")
    val jdwpPortFile: Path get() = dataDir.resolve("jdwp-port")
    val stateFile: Path get() = dataDir.resolve("state.json")
    val deploymentsDir: Path get() = dataDir.resolve("deployments")
    val pointersFile: Path get() = dataDir.resolve("pointers.json")

    /** Hot-classpath dir for Compose hot-reload runs: pushed class deltas land here. */
    val hotClasspathDir: Path get() = dataDir.resolve("hot")

    fun deploymentDir(sha: String): Path = deploymentsDir.resolve(sha)

    fun ensure() {
        Files.createDirectories(deploymentsDir)
    }
}

/** Load the persisted machine id, generating and storing one on first run. */
fun loadOrCreateMachineId(paths: AgentPaths): String {
    Files.createDirectories(paths.dataDir)
    val file = paths.machineIdFile
    if (Files.exists(file)) {
        val existing = Files.readString(file).trim()
        if (existing.isNotEmpty()) return existing
    }
    val id = UUID.randomUUID().toString()
    Files.writeString(file, id)
    return id
}

/** The persisted preferred JDWP port, or null if none was ever stored. */
fun readPersistedJdwpPort(paths: AgentPaths): Int? = runCatching {
    if (Files.exists(paths.jdwpPortFile)) Files.readString(paths.jdwpPortFile).trim().toIntOrNull() else null
}.getOrNull()

/** Persist the preferred JDWP port for future agent runs. */
fun persistJdwpPort(paths: AgentPaths, port: Int) {
    runCatching {
        Files.createDirectories(paths.dataDir)
        Files.writeString(paths.jdwpPortFile, port.toString())
    }
}
