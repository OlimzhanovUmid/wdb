package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.Handshake
import uz.disastrouspumpkin.wdb.protocol.StreamKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * An open port forward. Binds [localPort] on the dev machine's loopback and relays
 * every accepted connection to a loopback port on the agent's machine (design D5/D19).
 * Independent of control and log streams: a stalled forward affects nothing else.
 */
class Tunnel internal constructor(
    val localPort: Int,
    private val serverSocket: ServerSocket,
    private val acceptJob: Job,
) : Closeable {
    override fun close() {
        runCatching { serverSocket.close() }
        acceptJob.cancel()
    }
}

/**
 * Open a forward from a local loopback port to [remoteLoopbackPort] on the agent.
 * Pass [localPort] = 0 to let the OS choose (read it back from [Tunnel.localPort]).
 */
suspend fun openTunnel(
    scope: CoroutineScope,
    address: AgentAddress,
    remoteLoopbackPort: Int,
    localPort: Int = 0,
): Tunnel {
    val server = withContext(Dispatchers.IO) {
        ServerSocket(localPort, 50, InetAddress.getLoopbackAddress())
    }
    val acceptJob = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val local = server.accept()
                launch(Dispatchers.IO) { serveTunnelConnection(local, address, remoteLoopbackPort) }
            }
        } catch (_: Throwable) {
            // server socket closed — stop accepting
        }
    }
    // Close the listening socket when the accept loop ends — whether via Tunnel.close()
    // or the owning scope being cancelled — so a blocked accept() unblocks and nothing leaks.
    acceptJob.invokeOnCompletion { runCatching { server.close() } }
    return Tunnel(server.localPort, server, acceptJob)
}

private suspend fun serveTunnelConnection(local: Socket, address: AgentAddress, remotePort: Int) {
    val conn = try {
        Connection.open(address, Handshake(StreamKind.TUNNEL, tunnelPort = remotePort))
    } catch (t: Throwable) {
        runCatching { local.close() }
        return
    }
    val closer = {
        runCatching { local.close() }
        runCatching { conn.close() }
        Unit
    }
    // Capture streams while both sockets are open; obtaining them after the paired
    // relay closes its socket would throw "Socket is closed".
    val localIn = local.getInputStream()
    val localOut = local.getOutputStream()
    try {
        coroutineScope {
            // Cancellation can't interrupt the blocking relay reads; close the sockets
            // on cancel/completion so the reads throw and the relay threads exit.
            coroutineContext.job.invokeOnCompletion { closer() }
            launch(Dispatchers.IO) { relay(localIn, conn.output, closer) }
            launch(Dispatchers.IO) { relay(conn.input, localOut, closer) }
        }
    } finally {
        closer()
    }
}

/** Copy [from] -> [to] until EOF/error, then trip [onDone] to unblock the paired relay. */
private fun relay(from: InputStream, to: OutputStream, onDone: () -> Unit) {
    val buf = ByteArray(32 * 1024)
    try {
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
            to.flush()
        }
    } catch (_: Throwable) {
        // socket closed — fall through to onDone
    } finally {
        onDone()
    }
}
