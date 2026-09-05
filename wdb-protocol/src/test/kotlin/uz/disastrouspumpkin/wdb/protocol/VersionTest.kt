package uz.disastrouspumpkin.wdb.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {

    @Test
    fun `same major is compatible`() {
        assertTrue(isCompatible(1, 1))
        negotiate(1, 1) // does not throw
    }

    @Test
    fun `different major is incompatible and throws typed error`() {
        assertFalse(isCompatible(1, 2))
        val e = assertFailsWith<ProtocolVersionException> { negotiate(PROTOCOL_VERSION, PROTOCOL_VERSION + 1) }
        assertEquals(PROTOCOL_VERSION, e.localVersion)
        assertEquals(PROTOCOL_VERSION + 1, e.remoteVersion)
    }

    @Test
    fun `handshake and ack round-trip`() {
        val hs = Handshake(kind = StreamKind.TUNNEL, tunnelPort = 5005)
        assertEquals(hs, MessageCodec.decode<Handshake>(MessageCodec.encode(hs)))

        val ack = HandshakeAck(ok = false, error = ProtocolError(ErrorCode.VERSION_MISMATCH, "bad"))
        assertEquals(ack, MessageCodec.decode<HandshakeAck>(MessageCodec.encode(ack)))
    }
}
