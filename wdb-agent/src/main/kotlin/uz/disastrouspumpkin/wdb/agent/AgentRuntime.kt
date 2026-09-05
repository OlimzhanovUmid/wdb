package uz.disastrouspumpkin.wdb.agent

import java.io.Closeable

/**
 * Assembles and owns the agent's collaborators for one run: deployment store,
 * persisted state, log hub, supervisor, connection server, discovery responder.
 * Constructing it starts nothing; call [start].
 */
class AgentRuntime(val config: AgentConfig) : Closeable {
    val hub = LogHub()
    val store = DeploymentStore(config.paths)
    val state = AgentState(config.paths)
    val supervisor = Supervisor(config, store, state, hub)

    /** Self-update is available only for a versioned install (config.installBase set). */
    val selfUpdater: SelfUpdater? = config.installBase?.let { base ->
        SelfUpdater(AgentInstallLayout(base)) { productionRestart(base) }
    }
    /** Hot reload is available only when a CHR agent jar is bundled (config.hotReloadAgentJar set). */
    val hotReload: HotReloadCoordinator? = config.hotReloadAgentJar?.let { jar ->
        ChrHotReloadCoordinator(config.paths.hotClasspathDir, jar)
    }
    val server = AgentServer(config, store, supervisor, hub, selfUpdater, hotReload)
    val discovery = DiscoveryResponder(config, supervisor, state, server::port)

    /** Start listeners and honour the persisted desired state (relaunch if RUNNING). */
    fun start() {
        server.start()
        discovery.start()
        supervisor.applyDesiredOnStartup()
    }

    override fun close() {
        runCatching { discovery.close() }
        runCatching { server.close() }
        runCatching { supervisor.shutdown() }
    }

    /** The actual bound TCP port (useful when config asked for port 0 in tests). */
    fun port(): Int = server.port()
}
