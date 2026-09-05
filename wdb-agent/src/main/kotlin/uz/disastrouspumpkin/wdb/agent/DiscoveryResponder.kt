package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.DiscoveryAnswer
import uz.disastrouspumpkin.wdb.protocol.DiscoveryQuery
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import uz.disastrouspumpkin.wdb.protocol.isCompatible
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Answers discovery queries by unicast (design D4), so a dev machine needs no
 * inbound firewall rule. Reports identity, the agent's TCP address, and current
 * app/desired state, echoing the query nonce.
 */
class DiscoveryResponder(
    private val config: AgentConfig,
    private val supervisor: Supervisor,
    private val state: AgentState,
    private val advertisedTcpPort: () -> Int,
) : Closeable {
    // SO_REUSEADDR so a fresh agent can rebind the discovery port right after a
    // self-update handoff (old socket may briefly linger), instead of crashing on bind.
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(config.udpPort))
    }
    private val host = localIpv4()

    /** The actually-bound UDP port (useful when config asked for port 0 in tests). */
    fun port(): Int = socket.localPort

    fun start() {
        thread(isDaemon = true, name = "wdb-discovery") {
            val buf = ByteArray(64 * 1024)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Throwable) {
                    break
                }
                val query = runCatching {
                    MessageCodec.decode<DiscoveryQuery>(packet.data.copyOf(packet.length))
                }.getOrNull() ?: continue
                if (!isCompatible(uz.disastrouspumpkin.wdb.protocol.PROTOCOL_VERSION, query.protocolVersion)) continue

                val status = supervisor.status()
                val answer = DiscoveryAnswer(
                    machineId = config.machineId,
                    name = config.machineName,
                    host = host,
                    port = advertisedTcpPort(),
                    appState = status.appState,
                    desiredState = state.desiredState(),
                    nonce = query.nonce,
                )
                val bytes = MessageCodec.encode(answer)
                runCatching {
                    socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** First site-local IPv4 address, or loopback if none (dev/single-box). */
internal fun localIpv4(): String {
    runCatching {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
            }
        }
    }
    return "127.0.0.1"
}
