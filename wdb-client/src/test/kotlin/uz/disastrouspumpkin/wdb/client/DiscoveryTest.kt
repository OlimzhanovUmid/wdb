package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.DesiredState
import uz.disastrouspumpkin.wdb.protocol.DiscoveryAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryTest {

    private fun answer(id: String, name: String, nonce: String) = DiscoveryAnswer(
        machineId = id, name = name, host = "10.0.0.1", port = 7420,
        appState = AppState.RUNNING, desiredState = DesiredState.RUNNING, nonce = nonce,
    )

    @Test
    fun `de-duplicates by machine id, last answer wins`() {
        val result = dedupeAnswers(
            listOf(
                answer("id1", "wall-1", "n"),
                answer("id1", "wall-1-renamed", "n"),
                answer("id2", "wall-2", "n"),
            ),
            "n",
        )
        assertEquals(2, result.size)
        assertEquals("wall-1-renamed", result.first { it.id == "id1" }.name)
    }

    @Test
    fun `drops answers with a mismatched nonce`() {
        val result = dedupeAnswers(
            listOf(answer("id1", "wall-1", "n"), answer("id2", "wall-2", "OTHER")),
            "n",
        )
        assertEquals(listOf("id1"), result.map { it.id })
    }

    @Test
    fun `empty network yields empty set`() {
        assertTrue(dedupeAnswers(emptyList(), "n").isEmpty())
    }
}
