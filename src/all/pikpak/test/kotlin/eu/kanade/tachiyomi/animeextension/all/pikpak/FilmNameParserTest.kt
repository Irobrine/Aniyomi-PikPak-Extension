package eu.kanade.tachiyomi.animeextension.all.pikpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmNameParserTest {

    // ============================== Episodes ==============================

    @Test
    fun `parses episode numbers from common release formats`() {
        assertEquals(9, FilmNameParser.parseEpisodeNumber("Show E09.mkv"))
        assertEquals(9, FilmNameParser.parseEpisodeNumber("Show - 09.mkv"))
        assertEquals(9, FilmNameParser.parseEpisodeNumber("Show [09].mkv"))
        assertEquals(9, FilmNameParser.parseEpisodeNumber("Show Episode 9.mkv"))
        assertEquals(10, FilmNameParser.parseEpisodeNumber("Show S02E10.mkv"))
        assertEquals(9, FilmNameParser.parseEpisodeNumber("Show - 09v2.mkv"))
    }

    @Test
    fun `parses episode numbers from bracket and parenthesis forms`() {
        assertEquals(5, FilmNameParser.parseEpisodeNumber("Show (05).mkv"))
        assertEquals(7, FilmNameParser.parseEpisodeNumber("Show [E07].mkv"))
        assertEquals(8, FilmNameParser.parseEpisodeNumber("Show (E08).mkv"))
    }

    @Test
    fun `parses bare trailing numbers but not resolutions`() {
        assertEquals(12, FilmNameParser.parseEpisodeNumber("Show 12.mkv"))
        assertNull(FilmNameParser.parseEpisodeNumber("Show 1080p.mkv"))
        assertNull(FilmNameParser.parseEpisodeNumber("Show.mkv"))
    }

    @Test
    fun `half episodes keep their integer part`() {
        assertEquals(5, FilmNameParser.parseEpisodeNumber("Show - 05.5.mkv"))
    }

    @Test
    fun `numbers missing files contiguously in natural order`() {
        val numbers = FilmNameParser.numberEpisodes(
            listOf("a" to 2, "b" to null, "c" to 4, "d" to null),
        )
        assertEquals(listOf(2, 3, 4, 5), numbers)
    }

    @Test
    fun `numbers a list without detected numbers from one`() {
        val numbers = FilmNameParser.numberEpisodes(listOf("a" to null, "b" to null))
        assertEquals(listOf(1, 2), numbers)
    }

    @Test
    fun `formats episode titles with two digits`() {
        assertEquals("E01", FilmNameParser.formatEpisode(1))
        assertEquals("E10", FilmNameParser.formatEpisode(10))
        assertEquals("E100", FilmNameParser.formatEpisode(100))
    }

    // ============================== Ordering ==============================

    @Test
    fun `natural order puts two before ten`() {
        assertTrue(FilmNameParser.naturalCompare("Show E2.mkv", "Show E10.mkv") < 0)
        assertTrue(FilmNameParser.naturalCompare("Show 09.mkv", "Show 10.mkv") < 0)
    }

    @Test
    fun `natural order is case insensitive`() {
        assertTrue(FilmNameParser.naturalCompare("a", "B") < 0)
        assertEquals(0, FilmNameParser.naturalCompare("Show", "show"))
    }

    @Test
    fun `natural sort orders a realistic episode folder`() {
        val files = listOf("E10.mkv", "E2.mkv", "E1.mkv", "E11.mkv")
        assertEquals(listOf("E1.mkv", "E2.mkv", "E10.mkv", "E11.mkv"), FilmNameParser.sortInNaturalOrder(files))
    }

    // =============================== Seasons ==============================

    @Test
    fun `parses season urls`() {
        assertEquals("id" to "Season 2", FilmNameParser.parseSeasonUrl("id|Season 2"))
        assertEquals("id" to null, FilmNameParser.parseSeasonUrl("id"))
        assertEquals("id" to null, FilmNameParser.parseSeasonUrl("id|"))
        assertEquals("id" to "Show | A", FilmNameParser.parseSeasonUrl("id|Show | A"))
    }

    @Test
    fun `detects the season folder below the anime folder`() {
        assertEquals("Season 2", FilmNameParser.pathSeasonKey("Show", "/Show/Season 2/E01.mkv", "E01.mkv"))
        assertEquals("Extras", FilmNameParser.pathSeasonKey("Show", "/Show/Extras/E01.mkv", "E01.mkv"))
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show/E01.mkv", "E01.mkv"))
    }

    @Test
    fun `detects season keys from file names`() {
        assertEquals("S2", FilmNameParser.seasonKeyFromFileName("Show S02E01.mkv"))
        assertEquals("S1", FilmNameParser.seasonKeyFromFileName("Show Season 1 - 01.mkv"))
        assertEquals("SP", FilmNameParser.seasonKeyFromFileName("Show SP1.mkv"))
        assertNull(FilmNameParser.seasonKeyFromFileName("Show E01.mkv"))
    }

    @Test
    fun `uses file name prefixes as seasons only with several prefixes`() {
        val seasons = FilmNameParser.assignFileNameSeasons(
            listOf("Show A - 01.mkv", "Show B - 01.mkv"),
            "Show",
        )
        assertEquals(listOf("Show A", "Show B"), seasons)

        val tagged = FilmNameParser.assignFileNameSeasons(
            listOf("Show A 1080p - 01.mkv", "Show B 720p - 01.mkv"),
            "Collection",
        )
        assertEquals(listOf("Show A", "Show B"), tagged)
    }

    @Test
    fun `does not invent seasons for a single prefix`() {
        val seasons = FilmNameParser.assignFileNameSeasons(
            listOf("Show - 01.mkv", "Show - 02.mkv"),
            "Show",
        )
        assertEquals(listOf(null, null), seasons)
    }

    @Test
    fun `marks unlabelled episodes as other only when seasons exist`() {
        assertEquals(listOf(null, null), FilmNameParser.withOtherSeason(listOf(null, null)))
        assertEquals(listOf("S1", "Other"), FilmNameParser.withOtherSeason(listOf("S1", null)))
    }

    @Test
    fun `orders seasons numerically with other last`() {
        val ordered = FilmNameParser.orderedSeasons(listOf("Season 10", "Season 2", "Other", "Season 1"))
        assertEquals(listOf("Season 1", "Season 2", "Season 10", "Other"), ordered)
    }

    @Test
    fun `builds season display titles`() {
        assertEquals("Show S02", FilmNameParser.seasonDisplayTitle("Show", "Season 2"))
        assertEquals("Show Specials", FilmNameParser.seasonDisplayTitle("Show", "SP"))
        assertEquals("Show Dear Sister", FilmNameParser.seasonDisplayTitle("Show", "Dear Sister"))
    }

    @Test
    fun `season number is minus one when unknown`() {
        assertEquals(3.0, FilmNameParser.seasonNumberFromLabel("Season 3"), 0.0)
        assertEquals(-1.0, FilmNameParser.seasonNumberFromLabel("Dear Sister"), 0.0)
    }

    // ================================ Names ===============================

    @Test
    fun `cleans release group quality and extension noise`() {
        assertEquals("Horimiya", FilmNameParser.cleanAnimeName("[Group] Horimiya [1080p]"))
        assertEquals("Horimiya", FilmNameParser.cleanAnimeName("Horimiya.mkv"))
        assertEquals("Show - 01", FilmNameParser.cleanAnimeName("[SubsPlease] Show - 01 [1080p].mkv"))
        assertEquals("Show", FilmNameParser.cleanAnimeName("Show 1080p"))
        assertEquals("Show", FilmNameParser.cleanAnimeName("Show [1080]"))
        assertEquals("Show", FilmNameParser.cleanAnimeName("Show x265 10bit"))
        assertEquals("Show", FilmNameParser.cleanAnimeName("Show [Group]"))
        assertEquals("Show [Oshi no Ko]", FilmNameParser.cleanAnimeName("Show [Oshi no Ko]"))
        assertEquals("Charlotte's Web", FilmNameParser.cleanAnimeName("Charlotte's Web"))
    }

    @Test
    fun `keeps the original name when cleaning removes everything`() {
        assertEquals("[1080p]", FilmNameParser.cleanAnimeName("[1080p]"))
    }

    @Test
    fun `builds metadata search titles without years and tags`() {
        assertEquals("Show", FilmNameParser.metadataSearchTitle("[Group] Show (2020) [TV]"))
        // Bare years are kept: they can be part of the title (e.g. "Blade Runner 2049").
        assertEquals("Show 2020", FilmNameParser.metadataSearchTitle("Show 2020"))
        assertEquals("Show", FilmNameParser.metadataSearchTitle("Show [1080p]"))
    }

    @Test
    fun `extracts a release year for metadata validation`() {
        assertEquals(2020, FilmNameParser.metadataYear("[Group] Show (2020)"))
        assertEquals(1999, FilmNameParser.metadataYear("Show 1999 1080p"))
        assertNull(FilmNameParser.metadataYear("Show 1080p"))
        assertNull(FilmNameParser.metadataYear("2001: A Space Odyssey"))
    }

    @Test
    fun `detects video files by extension`() {
        assertTrue(FilmNameParser.isVideoFile("a.mkv"))
        assertTrue(FilmNameParser.isVideoFile("a.MP4"))
        assertFalse(FilmNameParser.isVideoFile("a.srt"))
        assertFalse(FilmNameParser.isVideoFile("a"))
    }

    // ================================ Dates ===============================

    @Test
    fun `parses utc timestamps`() {
        assertEquals(1_704_067_200_000L, FilmNameParser.parseDate("2024-01-01T00:00:00"))
        assertEquals(1_704_067_200_000L, FilmNameParser.parseDate("2024-01-01T00:00:00.000Z"))
    }

    @Test
    fun `applies the utc offset`() {
        assertEquals(1_704_067_200_000L, FilmNameParser.parseDate("2024-01-01T08:00:00.000+08:00"))
        assertEquals(1_704_067_200_000L, FilmNameParser.parseDate("2023-12-31T19:00:00-05:00"))
        assertEquals(1_704_067_200_000L, FilmNameParser.parseDate("2024-01-01T08:00:00+0800"))
    }

    @Test
    fun `returns zero for blank or malformed dates`() {
        assertEquals(0L, FilmNameParser.parseDate(null))
        assertEquals(0L, FilmNameParser.parseDate(""))
        assertEquals(0L, FilmNameParser.parseDate("yesterday"))
    }

    @Test
    fun `parses epoch seconds and millis expiry`() {
        assertEquals(1_704_067_200_000L, FilmNameParser.parseExpiryMillis("1704067200"))
        assertEquals(1_704_067_200_000L, FilmNameParser.parseExpiryMillis("1704067200000"))
        assertEquals(1_704_067_200_000L, FilmNameParser.parseExpiryMillis("2024-01-01T00:00:00Z"))
        assertNull(FilmNameParser.parseExpiryMillis(null))
        assertNull(FilmNameParser.parseExpiryMillis(""))
    }

    @Test
    fun `ranks stream qualities from labels`() {
        assertEquals(1080, PikPakClient.streamQualityRank("1080p"))
        assertEquals(720, PikPakClient.streamQualityRank("PikPak 720p"))
        assertEquals(0, PikPakClient.streamQualityRank("Original"))
    }
}
