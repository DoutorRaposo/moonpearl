package io.github.doutorraposo.moonpearl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsuPackTest {
    private fun names(vararg n: String) = n.map { it to "id/$it" }

    @Test
    fun detectsTracksOfThePack() {
        val pack = MsuPack.detect(names("alttp_msu-1.pcm", "alttp_msu-2.pcm", "alttp_msu-10.pcm", "readme.txt", "cover.png"))!!
        assertEquals("alttp_msu", pack.prefix)
        assertEquals(MsuPack.Format.PCM, pack.format)
        assertEquals(listOf(1, 2, 10), pack.tracks.map { it.number })
        assertFalse(pack.hasDeluxeTracks)
    }

    @Test
    fun picksTheLargestGroupAndSpotsDeluxe() {
        val pack = MsuPack.detect(
            names("other-1.pcm") + (1..40).map { "Orchestra-$it.OPUZ" to "id/$it" },
        )!!
        assertEquals("Orchestra", pack.prefix)
        assertEquals(MsuPack.Format.OPUZ, pack.format)
        assertEquals(40, pack.tracks.size)
        assertTrue(pack.hasDeluxeTracks)
    }

    @Test
    fun standardPackWithTrack35IsNotDeluxe() {
        // A real standard pack: tracks 1-35, no Deluxe themes (those start at 37).
        val pack = MsuPack.detect((1..35).map { "psm-$it.pcm" to "id/$it" })!!
        assertFalse(pack.hasDeluxeTracks)
        assertTrue(MsuPack.detect((1..37).map { "psm-$it.pcm" to "id/$it" })!!.hasDeluxeTracks)
    }

    @Test
    fun nothingToDetect() {
        assertNull(MsuPack.detect(names("song.mp3", "pack.zip", "track1.pcm")))
    }

    @Test
    fun iniValuesFollowUpstreamRules() {
        val pcm = MsuPack.Pack("p", MsuPack.Format.PCM, emptyList())
        val opuz = MsuPack.Pack("p", MsuPack.Format.OPUZ, emptyList())
        assertEquals("true", MsuPack.iniValues(pcm, false)["EnableMSU"])
        assertEquals("44100", MsuPack.iniValues(pcm, false)["AudioFreq"])
        assertEquals("deluxe", MsuPack.iniValues(pcm, true)["EnableMSU"])
        assertEquals("deluxe-opuz", MsuPack.iniValues(opuz, true)["EnableMSU"])
        assertEquals("48000", MsuPack.iniValues(opuz, false)["AudioFreq"])
        assertEquals("msu/track-", MsuPack.iniValues(opuz, false)["MSUPath"])
    }
}
