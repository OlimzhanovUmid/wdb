package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.DesiredState
import uz.disastrouspumpkin.wdb.protocol.DiscoveryAnswer

/** Where an agent can be reached. */
data class AgentAddress(val host: String, val port: Int)

/** A demo-wall machine as seen through discovery or the last-seen cache. */
data class Machine(
    val id: String,
    val name: String,
    val address: AgentAddress,
    val appState: AppState? = null,
    val desiredState: DesiredState? = null,
)

fun DiscoveryAnswer.toMachine(): Machine =
    Machine(
        id = machineId,
        name = name,
        address = AgentAddress(host, port),
        appState = appState,
        desiredState = desiredState,
    )
