package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.FrameCodec
import uz.disastrouspumpkin.wdb.protocol.Handshake
import uz.disastrouspumpkin.wdb.protocol.HandshakeAck
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import uz.disastrouspumpkin.wdb.protocol.ProtocolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket

/** Raised when an agent rejects a handshake (e.g. version mismatch). */
class HandshakeRejectedException(val error: ProtocolError?) :
    Exception("agent rejected handshake: ${error?.code} ${error?.message}")

/**
 * A single TCP connection to an agent, after a completed [Handshake]/[HandshakeAck].
 * Frames are read/written with [FrameCodec]; a tunnel connection uses the raw
 * [socket] streams directly after the ack. Closing the connection closes the socket,
 * which is also how a blocked read is cancelled (see [withCancellationClosing]).
 */
class Connection internal constructor(
    val socket: Socket,
    val input: DataInputStream,
    val output: DataOutputStream,
    val ack: HandshakeAck,
) : Closeable {

    fun writeFrame(bytes: ByteArray) = FrameCodec.writeFrame(output, bytes)

    fun readFrame(): ByteArray? = FrameCodec.readFrame(input)

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 4000

        suspend fun open(
            address: AgentAddress,
            handshake: Handshake,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        ): Connection = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address.host, address.port), connectTimeoutMs)
                socket.tcpNoDelay = true
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                FrameCodec.writeFrame(output, MessageCodec.encode(handshake))
                val ackBytes = FrameCodec.readFrame(input) ?: throw EOFException("no handshake ack from agent")
                val ack = MessageCodec.decode<HandshakeAck>(ackBytes)
                if (!ack.ok) throw HandshakeRejectedException(ack.error)
                Connection(socket, input, output, ack)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }
}
