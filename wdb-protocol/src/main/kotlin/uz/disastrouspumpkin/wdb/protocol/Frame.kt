package uz.disastrouspumpkin.wdb.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Raised when a length prefix exceeds [FrameCodec.MAX_FRAME_SIZE] or is negative. */
class FrameTooLargeException(val length: Int) :
    Exception("frame length $length outside [0, ${FrameCodec.MAX_FRAME_SIZE}]")

/**
 * Length-prefixed framing used by the control, push and logs connections:
 * a big-endian `u32` length followed by exactly that many payload bytes.
 *
 * A frame carries opaque bytes. On control/logs connections the payload is UTF-8
 * JSON (see [MessageCodec]); on a push connection the manifest and result frames
 * are JSON while blob frames are raw bytes. Tunnel connections are unframed after
 * the handshake and never go through this codec.
 */
object FrameCodec {
    /**
     * Hard cap on a single frame; oversized frames are rejected rather than allocated. 8 MiB covers a
     * full-HD screenshot sent as a raw PNG blob frame (change add-binary-screenshot-transport).
     */
    const val MAX_FRAME_SIZE: Int = 8 * 1024 * 1024

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        if (payload.size > MAX_FRAME_SIZE) throw FrameTooLargeException(payload.size)
        val dout = out as? DataOutputStream ?: DataOutputStream(out)
        dout.writeInt(payload.size)
        dout.write(payload)
        dout.flush()
    }

    /**
     * Read one frame. Returns `null` on a clean end of stream (no bytes at all),
     * throws [EOFException] on a truncated frame and [FrameTooLargeException] on a
     * bad length prefix.
     */
    fun readFrame(inp: InputStream): ByteArray? {
        val din = inp as? DataInputStream ?: DataInputStream(inp)
        val len = try {
            din.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (len < 0 || len > MAX_FRAME_SIZE) throw FrameTooLargeException(len)
        val buf = ByteArray(len)
        din.readFully(buf)
        return buf
    }
}
