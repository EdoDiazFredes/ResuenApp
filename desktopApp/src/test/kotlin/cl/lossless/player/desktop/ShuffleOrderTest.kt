package cl.lossless.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleOrderTest {

    @Test
    fun startsWithCurrentTrackAndCoversWholeQueue() {
        repeat(20) {
            val order = shuffledOrder(10, 4)
            assertEquals(4, order.first())
            assertEquals((0 until 10).toList(), order.sorted())
        }
    }

    @Test
    fun invalidCurrentStillGivesFullPermutation() {
        assertEquals((0 until 5).toList(), shuffledOrder(5, -1).sorted())
    }
}
