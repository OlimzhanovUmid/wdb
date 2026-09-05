package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.DiscoveryAnswer
import uz.disastrouspumpkin.wdb.protocol.DiscoveryQuery
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID

/** Default UDP port agents listen on for discovery queries. */
const val DEFAULT_DISCOVERY_PORT: Int = 7421

/**
 * Fold a batch of raw answers into the machine set: keep only answers whose nonce
 * matches the query, and de-duplicate by machine id (last answer wins). Pure, so
 * it is unit-tested without sockets.
 */
fun dedupeAnswers(answers: List<DiscoveryAnswer>, expectedNonce: String): List<Machine> {
    val byId = LinkedHashMap<String, Machine>()
    for (a in answers) {
        if (a.nonce != expectedNonce) continue
        byId[a.machineId] = a.toMachine()
    }
    return byId.values.toList()
}

/**
 * Broadcast a discovery query and collect unicast answers for [windowMs].
 * The query is sent twice (start and mid-window) for reliability (design D4).
 * Needs no inbound firewall rule on the dev machine: replies are unicast responses
 * to a broadcast this host just sent.
 */
suspend fun discover(
    port: Int = DEFAULT_DISCOVERY_PORT,
    windowMs: Long = 1500,
): List<Machine> = withContext(Dispatchers.IO) {
    val nonce = UUID.randomUUID().toString()
    val queryBytes = MessageCodec.encode(DiscoveryQuery(nonce = nonce))
    val answers = ArrayList<DiscoveryAnswer>()

    DatagramSocket().use { socket ->
        socket.broadcast = true
        val targets = broadcastTargets()

        fun sendQuery() {
            for (target in targets) {
                runCatching { socket.send(DatagramPacket(queryBytes, queryBytes.size, target, port)) }
            }
        }

        sendQuery()
        val deadline = System.currentTimeMillis() + windowMs
        var reSent = false
        val buf = ByteArray(64 * 1024)
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            if (!reSent && remaining <= windowMs / 2) {
                sendQuery(); reSent = true
            }
            socket.soTimeout = remaining.coerceIn(1, windowMs).toInt()
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            }
            runCatching {
                MessageCodec.decode<DiscoveryAnswer>(packet.data.copyOf(packet.length))
            }.getOrNull()?.let { answers.add(it) }
        }
    }
    dedupeAnswers(answers, nonce)
}

private fun broadcastTargets(): List<InetAddress> {
    val targets = LinkedHashSet<InetAddress>()
    targets.add(InetAddress.getByName("255.255.255.255"))
    runCatching {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (ia in nif.interfaceAddresses) {
                ia.broadcast?.let { targets.add(it) }
            }
        }
    }
    return targets.toList()
}
