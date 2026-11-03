package cl.lossless.player.playback.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoMediaIdsTest {

    @Test
    fun trackIdKeepsParentAndUri() {
        val parent = AutoMediaIds.ALBUM + "primary:Music/Rage|rage against the machine"
        val uri = "content://media/external/audio/media/42"
        assertEquals(parent to uri, AutoMediaIds.parseTrack(AutoMediaIds.track(parent, uri)))
    }

    @Test
    fun otherIdsAreNotTracks() {
        assertNull(AutoMediaIds.parseTrack(AutoMediaIds.ALBUMS))
        assertNull(AutoMediaIds.parseTrack("content://media/external/audio/media/42"))
        assertNull(AutoMediaIds.parseTrack("track:sin separador"))
    }

    @Test
    fun folderIdRoundTrip() {
        val segments = listOf("primary", "Music", "Rock")
        assertEquals(segments, AutoMediaIds.folderSegments(AutoMediaIds.folder(segments)))
    }

    @Test
    fun pagination() {
        val items = (1..10).toList()
        assertEquals(listOf(1, 2, 3, 4), AutoMediaIds.page(items, 0, 4))
        assertEquals(listOf(9, 10), AutoMediaIds.page(items, 2, 4))
        assertEquals(emptyList<Int>(), AutoMediaIds.page(items, 3, 4))
        assertEquals(items, AutoMediaIds.page(items, 0, Int.MAX_VALUE))
    }
}
