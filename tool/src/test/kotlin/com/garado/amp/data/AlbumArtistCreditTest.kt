package com.garado.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** A semicolon separates multiple album artists; a comma is part of a name, not a separator. */
class AlbumArtistCreditTest {

    private fun track(albumArtist: String, artist: String = "Performer") = Track(
        id = "1",
        title = "t",
        artist = artist,
        album = "a",
        albumArtist = albumArtist,
        albumId = "al",
        coverArtId = null,
        durationMs = 0L,
        trackNumber = null,
        discNumber = null,
        year = null,
        playCount = 0,
        lastPlayedMs = 0L,
    )

    @Test
    fun `a semicolon separates two album artists`() {
        assertEquals(
            listOf("Johann Sebastian Bach", "Glenn Gould"),
            track("Johann Sebastian Bach; Glenn Gould").albumArtistNames(),
        )
    }

    @Test
    fun `a comma is part of the name, not a separator`() {
        for (name in listOf("Earth, Wind & Fire", "Emerson, Lake & Palmer", "Crosby, Stills & Nash")) {
            assertEquals(listOf(name), track(name).albumArtistNames())
        }
    }

    @Test
    fun `a single credit is left whole`() {
        assertEquals(listOf("Pau Casals"), track("Pau Casals").albumArtistNames())
    }

    @Test
    fun `spacing around the semicolon does not matter`() {
        assertEquals(listOf("A", "B", "C"), track("A;B ;  C").albumArtistNames())
    }

    @Test
    fun `empty entries are dropped rather than becoming a nameless artist`() {
        assertEquals(listOf("A", "B"), track("A;;B;").albumArtistNames())
    }

    @Test
    fun `no album artist falls back to the performer`() {
        assertEquals(listOf("Performer"), track("", artist = "Performer").albumArtistNames())
    }

    @Test
    fun `the primary credit is the first one`() {
        assertEquals("Johann Sebastian Bach", track("Johann Sebastian Bach; Glenn Gould").primaryAlbumArtist())
    }
}
