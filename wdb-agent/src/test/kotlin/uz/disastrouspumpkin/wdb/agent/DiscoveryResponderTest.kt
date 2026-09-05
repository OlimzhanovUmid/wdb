package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.DiscoveryAnswer
import uz.disastrouspumpkin.wdb.protocol.DiscoveryQuery
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryResponderTest {

    @Test
    fun `answers a discovery query by unicast with identity and address`() {
        val dir = Files.createTempDirectory("wdb-disc")
        val id = loadOrCreateMachineId(AgentPaths(dir))
        val runtime = AgentRuntime(AgentConfig(machineName = "wall-x", machineId = id, dataDir = dir, tcpPort = 0, udpPort = 0))
        runtime.start()
        try {
            val udpPort = runtime.discovery.port()
            DatagramSocket().use { socket ->
                socket.soTimeout = 3000
                val query = MessageCodec.encode(DiscoveryQuery(nonce = "abc123"))
                socket.send(DatagramPacket(query, query.size, InetAddress.getLoopbackAddress(), udpPort))

                val buf = ByteArray(64 * 1024)
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val answer = MessageCodec.decode<DiscoveryAnswer>(packet.data.copyOf(packet.length))

                assertEquals(id, answer.machineId)
                assertEquals("wall-x", answer.name)
                assertEquals(runtime.port(), answer.port) // points at the agent's TCP server
                assertEquals("abc123", answer.nonce)
            }
        } finally {
            runtime.close()
        }
    }
}
