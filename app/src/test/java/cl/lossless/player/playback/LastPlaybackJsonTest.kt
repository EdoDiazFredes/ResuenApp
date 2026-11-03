package cl.lossless.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastPlaybackJsonTest {

    @Test
    fun queueRoundTrips() {
        val entries = listOf(
            SavedEntry(
                mediaId = "content://media/external/audio/media/12",
                mimeType = "audio/flac",
                title = "Canción \"uno\"",
                artist = "Artista",
                album = "Álbum",
                albumArtist = "Artista",
                artworkUri = "content://cl.lossless.player.covers/a.jpg",
                description = "FLAC • 24-bit • 96 kHz",
            ),
            SavedEntry(mediaId = "content://media/external/audio/media/13"),
        )
        assertEquals(entries, LastPlaybackJson.decodeQueue(LastPlaybackJson.encodeQueue(entries)))
    }

    @Test
    fun brokenOrEmptyFileGivesEmptyQueue() {
        assertTrue(LastPlaybackJson.decodeQueue("").isEmpty())
        assertTrue(LastPlaybackJson.decodeQueue("{no es json").isEmpty())
        assertTrue(LastPlaybackJson.decodeQueue("""{"v":1}""").isEmpty())
    }

    @Test
    fun entriesWithoutIdAreSkipped() {
        val text = """{"v":1,"queue":[{"title":"sin id"},{"id":"content://x/1"}]}"""
        assertEquals(listOf(SavedEntry(mediaId = "content://x/1")), LastPlaybackJson.decodeQueue(text))
    }
}
