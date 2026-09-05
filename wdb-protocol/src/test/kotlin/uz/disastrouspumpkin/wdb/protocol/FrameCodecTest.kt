package uz.disastrouspumpkin.wdb.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrameCodecTest {

    @Test
    fun `round-trips multiple frames in order`() {
        val a = "hello".encodeToByteArray()
        val b = ByteArray(1000) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, a)
        FrameCodec.writeFrame(out, b)
        FrameCodec.writeFrame(out, ByteArray(0))

        val inp = ByteArrayInputStream(out.toByteArray())
        assertContentEquals(a, FrameCodec.readFrame(inp))
        assertContentEquals(b, FrameCodec.readFrame(inp))
        assertContentEquals(ByteArray(0), FrameCodec.readFrame(inp))
    }

    @Test
    fun `returns null on clean end of stream`() {
        val inp = ByteArrayInputStream(ByteArray(0))
        assertNull(FrameCodec.readFrame(inp))
    }

    @Test
    fun `throws on truncated frame body`() {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, "abcdef".encodeToByteArray())
        val truncated = out.toByteArray().copyOf(4 + 2) // length prefix + 2 of 6 bytes
        assertFailsWith<EOFException> { FrameCodec.readFrame(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun `rejects oversized frame on write`() {
        val out = ByteArrayOutputStream()
        val e = assertFailsWith<FrameTooLargeException> {
            FrameCodec.writeFrame(out, ByteArray(FrameCodec.MAX_FRAME_SIZE + 1))
        }
        assertEquals(FrameCodec.MAX_FRAME_SIZE + 1, e.length)
    }

    @Test
    fun `rejects oversized length prefix on read without allocating`() {
        val out = ByteArrayOutputStream()
        java.io.DataOutputStream(out).writeInt(FrameCodec.MAX_FRAME_SIZE + 1)
        assertFailsWith<FrameTooLargeException> {
            FrameCodec.readFrame(ByteArrayInputStream(out.toByteArray()))
        }
    }
}
