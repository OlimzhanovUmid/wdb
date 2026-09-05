package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.DesiredState
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import kotlinx.serialization.Serializable
import java.nio.file.Files

@Serializable
private data class PersistedState(val desiredState: DesiredState = DesiredState.STOPPED)

/**
 * Persists whether the app is meant to be running (design D15), so the wall comes
 * back by itself after a reboot and a deliberately stopped box stays quiet.
 */
class AgentState(private val paths: AgentPaths) {
    @Volatile
    private var desired: DesiredState = load()

    fun desiredState(): DesiredState = desired

    fun setDesired(state: DesiredState) {
        desired = state
        runCatching {
            Files.createDirectories(paths.dataDir)
            Files.write(paths.stateFile, MessageCodec.encode(PersistedState(state)))
        }
    }

    private fun load(): DesiredState = runCatching {
        if (Files.exists(paths.stateFile)) {
            MessageCodec.decode<PersistedState>(Files.readAllBytes(paths.stateFile)).desiredState
        } else {
            DesiredState.STOPPED
        }
    }.getOrDefault(DesiredState.STOPPED)
}
