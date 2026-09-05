package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.ControlRequest
import uz.disastrouspumpkin.wdb.protocol.ControlResponse
import uz.disastrouspumpkin.wdb.protocol.FrameCodec
import uz.disastrouspumpkin.wdb.protocol.Handshake
import uz.disastrouspumpkin.wdb.protocol.HandshakeAck
import uz.disastrouspumpkin.wdb.protocol.LogEvent
import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import uz.disastrouspumpkin.wdb.protocol.OkResponse
import uz.disastrouspumpkin.wdb.protocol.PushManifest
import uz.disastrouspumpkin.wdb.protocol.PushResult
import uz.disastrouspumpkin.wdb.protocol.ReloadBatch
import uz.disastrouspumpkin.wdb.protocol.ReloadChangeType
import uz.disastrouspumpkin.wdb.protocol.ReloadOutcome
import uz.disastrouspumpkin.wdb.protocol.ReloadResult
import uz.disastrouspumpkin.wdb.protocol.ScreenshotRequest
import uz.disastrouspumpkin.wdb.protocol.ScreenshotResponse
import uz.disastrouspumpkin.wdb.protocol.StreamKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal in-test agent: real TCP server on loopback that speaks the wire protocol.
 * Control returns [controlHandler]'s response; logs streams [logEvents] then closes;
 * tunnel echoes bytes (standing in for a loopback service); push accepts and acks.
 */
class FakeAgent(
    private val controlHandler: (ControlRequest) -> ControlResponse = { OkResponse },
    private val logEvents: List<LogEvent> = emptyList(),
    private val hangControl: Boolean = false,
    private val reloadHandler: (ReloadBatch) -> ReloadResult = { ReloadResult(ReloadOutcome.APPLIED) },
    /** PNG bytes returned for a ScreenshotRequest (header + raw blob frame); null → ok=false, no blob. */
    private val screenshotPng: ByteArray? = null,
) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val address: AgentAddress get() = AgentAddress("127.0.0.1", server.localPort)

    /** Batches received on RELOAD connections, in arrival order (for test assertions). */
    val reloadBatches: MutableList<ReloadBatch> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        Thread {
            try {
                while (true) {
                    val s = server.accept()
                    Thread { handle(s) }.apply { isDaemon = true }.start()
                }
            } catch (_: Throwable) { /* server closed */ }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(sock: Socket) {
        sock.use {
            val din = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            val hsBytes = FrameCodec.readFrame(din) ?: return
            val hs = MessageCodec.decode<Handshake>(hsBytes)
            FrameCodec.writeFrame(dout, MessageCodec.encode(HandshakeAck(ok = true)))
            when (hs.kind) {
                StreamKind.CONTROL -> {
                    if (hangControl) {
                        while (true) Thread.sleep(1000)
                    }
                    val reqBytes = FrameCodec.readFrame(din) ?: return
                    val req = MessageCodec.decode(ControlRequest.serializer(), reqBytes)
                    if (req is ScreenshotRequest) {
                        val png = screenshotPng
                        FrameCodec.writeFrame(dout, MessageCodec.encode(ControlResponse.serializer(), ScreenshotResponse(ok = png != null, error = if (png == null) "no screenshot" else null)))
                        if (png != null) FrameCodec.writeFrame(dout, png)
                    } else {
                        FrameCodec.writeFrame(dout, MessageCodec.encode(ControlResponse.serializer(), controlHandler(req)))
                    }
                }
                StreamKind.LOGS -> {
                    for (e in logEvents) {
                        FrameCodec.writeFrame(dout, MessageCodec.encode(LogEvent.serializer(), e))
                    }
                    // close to signal end-of-stream to the client flow
                }
                StreamKind.TUNNEL -> {
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = din.read(buf)
                        if (n < 0) break
                        dout.write(buf, 0, n)
                        dout.flush()
                    }
                }
                StreamKind.PUSH -> {
                    val manifest = MessageCodec.decode<PushManifest>(FrameCodec.readFrame(din)!!)
                    while (true) {
                        val f = FrameCodec.readFrame(din) ?: break
                        if (f.isEmpty()) break
                    }
                    FrameCodec.writeFrame(
                        dout,
                        MessageCodec.encode(
                            PushResult(ok = true, deployedSha = manifest.entries.first().sha256, restarted = manifest.restart),
                        ),
                    )
                }
                StreamKind.AGENT_UPDATE -> {
                    // Not exercised in client tests (covered against the real agent).
                }
                StreamKind.RELOAD -> {
                    val batch = MessageCodec.decode<ReloadBatch>(FrameCodec.readFrame(din)!!)
                    // Drain one blob (empty-frame terminated) per ADDED/MODIFIED entry.
                    for (e in batch.entries) {
                        if (e.changeType == ReloadChangeType.REMOVED) continue
                        while (true) {
                            val f = FrameCodec.readFrame(din) ?: break
                            if (f.isEmpty()) break
                        }
                    }
                    reloadBatches.add(batch)
                    FrameCodec.writeFrame(dout, MessageCodec.encode(reloadHandler(batch)))
                }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
