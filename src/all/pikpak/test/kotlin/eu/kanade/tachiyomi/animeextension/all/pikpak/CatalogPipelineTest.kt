package eu.kanade.tachiyomi.animeextension.all.pikpak

import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListPageResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDetectionTest {

    private fun parsed(name: String): ParsedEpisode? = EpisodeParser.parse(name)

    private fun number(name: String): Int? = EpisodeParser.parseNumber(name)

    @Test
    fun `recognises the common season and episode spellings`() {
        assertEquals(1, number("Show S01E01.mkv"))
        assertEquals(2, number("Show s1e2.mkv"))
        assertEquals(3, number("Show S01.E03.mkv"))
        assertEquals(4, number("Show S01 - E04.mkv"))
        assertEquals(5, number("Show 1x05.mkv"))
        assertEquals(6, number("Show 01x06.mkv"))
        assertEquals(7, number("Show EP07.mkv"))
        assertEquals(8, number("Show Ep. 08.mkv"))
        assertEquals(9, number("Show Episode 09.mkv"))
        assertEquals(10, number("Show E10.mkv"))
        assertEquals(11, number("Show [E11].mkv"))
        assertEquals(12, number("Show (E12).mkv"))
    }

    @Test
    fun `recognises multilingual episode labels`() {
        assertEquals(1, number("Show 第01话.mkv"))
        assertEquals(2, number("Show 第02話.mkv"))
        assertEquals(3, number("Show 3화.mkv"))
        assertEquals(4, number("Show Tập 04.mkv"))
        assertEquals(5, number("Show Folge 05.mkv"))
        assertEquals(6, number("Show Épisode 06.mkv"))
        assertEquals(7, number("Show Capítulo 07.mkv"))
        assertEquals(8, number("Show Odcinek 08.mkv"))
        assertEquals(9, number("Show Bölüm 09.mkv"))
        assertEquals(10, number("Show Эпизод 10.mkv"))
    }

    @Test
    fun `combines season words with episode words`() {
        val parsed = parsed("Show Saison 2 Épisode 3.mkv")
        assertEquals(3, parsed?.number)
        assertEquals(2, parsed?.season)

        val german = parsed("Show Staffel 2 Folge 5.mkv")
        assertEquals(5, german?.number)
        assertEquals(2, german?.season)

        val spanish = parsed("Show Temporada 2 Episodio 4.mkv")
        assertEquals(4, spanish?.number)
        assertEquals(2, spanish?.season)
    }

    @Test
    fun `supports absolute and numeric-only numberings`() {
        assertEquals(102, number("Show - 102.mkv"))
        assertEquals(102, number("Show 102.mkv"))
        assertEquals(102, number("Show [102].mkv"))
        assertEquals(1, number("01.mkv"))
        assertEquals(7, number("7.mkv"))
    }

    @Test
    fun `keeps decimals and episode ranges`() {
        val decimal = parsed("Show - 05.5.mkv")
        assertEquals(5.5, decimal?.value ?: 0.0, 0.0)
        assertEquals(EpisodeKind.DECIMAL, decimal?.kind)

        val explicitDecimal = parsed("Show E12.25.mkv")
        assertEquals(12.25, explicitDecimal?.value ?: 0.0, 0.0)

        val versionedDecimal = parsed("Show 05.5 v2.mkv")
        assertEquals(5.5, versionedDecimal?.value ?: 0.0, 0.0)
        assertEquals(2, versionedDecimal?.version)

        val range = parsed("Show S01E01-E03.mkv")
        assertEquals(1, range?.number)
        assertEquals(3.0, range?.endValue ?: 0.0, 0.0)
        assertTrue(range?.isRange == true)

        val dashRange = parsed("Show - 01-03.mkv")
        assertEquals(1, dashRange?.number)
        assertEquals(3.0, dashRange?.endValue ?: 0.0, 0.0)

        val tildeRange = parsed("Show 01~03.mkv")
        assertEquals(1, tildeRange?.number)
        assertEquals(3.0, tildeRange?.endValue ?: 0.0, 0.0)

        assertEquals(1.5, parsed("Show 1.5.mkv")?.value ?: 0.0, 0.0)
        assertEquals(1.2, parsed("Show 1.2.mkv")?.value ?: 0.0, 0.0)
        assertNull(parsed("Show 7.1.mkv"))
        assertNull(parsed("Show 5.1ch.mkv"))
        assertNull(parsed("Show [DDP5.1].mkv"))
        assertNull(parsed("Show AAC2.0.mkv"))
        assertNull(parsed("Show AAC 2.0.mkv"))
        assertEquals(5, parsed("Show x265 05.mkv")?.number)
    }

    @Test
    fun `detects seasons from the same file name`() {
        assertEquals(2, parsed("Show Season 2 - 05.mkv")?.season)
        assertEquals(5, parsed("Show Season 2 - 05.mkv")?.number)
        assertEquals(2, parsed("Show 2x05.mkv")?.season)
        assertEquals(5, parsed("Show 2x05.mkv")?.number)
        assertEquals(2, parsed("Show S02 - 05.mkv")?.season)
        assertEquals(5, parsed("Show S02 - 05.mkv")?.number)
        assertEquals(2, parsed("Show 第2季 第05话.mkv")?.season)
        assertEquals(5, parsed("Show 第2季 第05话.mkv")?.number)
        assertEquals(2, parsed("Show 2기 5화.mkv")?.season)
        assertEquals(5, parsed("Show 2기 5화.mkv")?.number)
    }

    @Test
    fun `handles special numbers without a season folder`() {
        assertEquals(EpisodeKind.SPECIAL, parsed("Show Special 2.mkv")?.kind)
        assertEquals(2, parsed("Show Special 2.mkv")?.number)
        assertEquals(EpisodeKind.SPECIAL, parsed("Show E00.mkv")?.kind)
        assertEquals(EpisodeKind.SPECIAL, parsed("Show S00E01.mkv")?.kind)
        assertNull(parsed("Show SP.mkv"))
        assertNull(parsed("Show (OVA).mkv"))
    }

    @Test
    fun `prefers the episode marker over numbers in the title`() {
        assertEquals(1, number("Mob Psycho 100 - 01.mkv"))
        assertEquals(1, number("86 - 01.mkv"))
        assertEquals(5, number("Show 2 - 05.mkv"))
        assertEquals(3, number("20th Century Boys 03.mkv"))
        assertEquals(1, number("Show 2020 - 01.mkv"))
        assertEquals(1, number("Show 1080 - 01.mkv"))
        assertEquals(5, number("Show 2160p 05.mkv"))
        assertEquals(2, number("Show 01 02.mkv"))
    }

    @Test
    fun `allows standalone absolute episode numbers that resemble codecs`() {
        assertEquals(264, number("Show 264.mkv"))
        assertEquals(265, number("Show 265.mkv"))
        assertNull(number("Show x264.mkv"))
        assertNull(number("Show h265.mkv"))
    }

    @Test
    fun `keeps release versions separate`() {
        assertEquals(2, parsed("Show - 05 v2.mkv")?.version)
        assertEquals(2, parsed("Show S01E05v2.mkv")?.version)
        assertEquals(3, parsed("Show E05 v3.mkv")?.version)
        assertEquals(1, parsed("Show E05 v1.mkv")?.version)
    }

    @Test
    fun `rejects years resolutions codecs and audio channels`() {
        assertNull(number("Show 2020.mkv"))
        assertNull(number("Show (2020).mkv"))
        assertNull(number("Show [2020].mkv"))
        assertNull(number("Show 1080p.mkv"))
        assertNull(number("Show 2160p.mkv"))
        assertNull(number("Show 1920x1080.mkv"))
        assertNull(number("Show x264.mkv"))
        assertNull(number("Show 10bit.mkv"))
        assertNull(number("Show 5.1.mkv"))
        assertNull(number("Show 60fps.mkv"))
        assertNull(number("Show 1080p 10bit.mkv"))
    }

    @Test
    fun `does not treat season part or disc numbers as episodes`() {
        assertNull(number("Show Season 2.mkv"))
        assertNull(number("Show Part 2.mkv"))
        assertNull(number("Show Cour 2.mkv"))
        assertNull(number("Show Disc 1.mkv"))
        assertNull(number("Show Volume 3.mkv"))
    }

    @Test
    fun `handles titles with brackets release groups and separators`() {
        assertEquals(1, number("[SubsPlease] Show - 01 [1080p].mkv"))
        assertEquals(2, number("[Group] Show_S01E02_1080p.mkv"))
        assertEquals(3, number("Show - 03 - The Title.mkv"))
        assertEquals(4, number("Show_04_1080p.mkv"))
        assertEquals(6, number("Show [Group] (06).mkv"))
    }

    @Test
    fun `recognises abbreviations and concatenated multi episode files`() {
        assertEquals(1, number("Show Ép 01.mkv"))
        assertEquals(2, number("Show Cap 02.mkv"))
        assertEquals(3, number("Show Odc 03.mkv"))
        assertEquals(4, number("Show Böl 04.mkv"))

        val multi = parsed("Show S01E01E02.mkv")
        assertEquals(1, multi?.number)
        assertEquals(2.0, multi?.endValue ?: 0.0, 0.0)
        assertEquals(1, multi?.season)

        val bareMulti = parsed("Show E05E06.mkv")
        assertEquals(5, bareMulti?.number)
        assertEquals(6.0, bareMulti?.endValue ?: 0.0, 0.0)
    }

    @Test
    fun `formats decimals ranges and specials for display`() {
        assertEquals("E05.5", FilmNameParser.formatEpisode(5.5))
        assertEquals("E05.25", FilmNameParser.formatEpisode(5.25))
        assertEquals("E01-E03", FilmNameParser.formatEpisode(1.0, 3.0))
        assertEquals("SP01", FilmNameParser.formatEpisode(1.0, null, EpisodeKind.SPECIAL))
    }

    @Test
    fun `normalises full width and control characters`() {
        assertEquals(5, number("Ｓｈｏｗ　Ｅ０５.mkv"))
        assertEquals("Show E05", FilmNameParser.cleanAnimeName("Ｓｈｏｗ\uFEFF　Ｅ０５.mkv"))
        assertEquals(3, number("Show\u200B - 03.mkv"))
    }

    @Test
    fun `strips episode suffixes from file titles`() {
        assertEquals("Show", FilmNameParser.titleFromFileName("[Group] Show - 01 [1080p].mkv"))
        assertEquals("Show", FilmNameParser.titleFromFileName("Show S02E03.mkv"))
        assertEquals("Show", FilmNameParser.titleFromFileName("Show (E05).mkv"))
        assertEquals("Show", FilmNameParser.titleFromFileName("Show [01].mkv"))
        assertEquals("Show", FilmNameParser.titleFromFileName("Show 第01话 [1080p].mkv"))
        assertEquals("Mob Psycho 100", FilmNameParser.titleFromFileName("Mob Psycho 100.mkv"))
        assertEquals("Show", FilmNameParser.metadataSearchTitle("[Group] Show - 01 [1080p].mkv"))
        assertEquals("Show", FilmNameParser.metadataSearchTitle("Show 第01话 [1080p].mkv"))
    }
}

class DirectoryAnalysisTest {

    @Test
    fun `keeps the season above discs and release groups`() {
        assertEquals(
            "Season 1",
            FilmNameParser.pathSeasonKey("Show", "/Show/Season 1/Disc 2/E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Season 1",
            FilmNameParser.pathSeasonKey("Show", "/Show/[Group]/Season 1/E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Season 1",
            FilmNameParser.pathSeasonKey("Show", "/Show/Season 1/Videos/E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Season 1",
            FilmNameParser.pathSeasonKey("Show", "/Show/[Season 1]/E01.mkv", "E01.mkv"),
        )
    }

    @Test
    fun `ignores disc-only and year-only folders`() {
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show/Disc 1/E01.mkv", "E01.mkv"))
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show/2020/E01.mkv", "E01.mkv"))
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show/Videos/E01.mkv", "E01.mkv"))
    }

    @Test
    fun `keeps free-form and special folders`() {
        assertEquals("Extras", FilmNameParser.pathSeasonKey("Show", "/Show/Extras/E01.mkv", "E01.mkv"))
        assertEquals("Specials", FilmNameParser.pathSeasonKey("Show", "/Show/Specials/E01.mkv", "E01.mkv"))
        assertEquals("OVA", FilmNameParser.pathSeasonKey("Show", "/Show/OVA/E01.mkv", "E01.mkv"))
        assertEquals("OVA 2", FilmNameParser.pathSeasonKey("Show", "/Show/OVA 2/E01.mkv", "E01.mkv"))
        assertEquals("Dear Sister", FilmNameParser.pathSeasonKey("Show", "/Show/Dear Sister/E01.mkv", "E01.mkv"))
    }

    @Test
    fun `combines parts and cours with their season`() {
        assertEquals(
            "Season 1 Part 2",
            FilmNameParser.pathSeasonKey("Show", "/Show/Season 1 Part 2/E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Season 2 Cour 1",
            FilmNameParser.pathSeasonKey("Show", "/Show/Season 2/Cour 1/E01.mkv", "E01.mkv"),
        )
        assertEquals("Cour 2", FilmNameParser.pathSeasonKey("Show", "/Show/Cour 2/E01.mkv", "E01.mkv"))
    }

    @Test
    fun `qualifies series folders when several share a parent`() {
        assertEquals(
            "Show A - Season 1",
            FilmNameParser.pathSeasonKey("Collection", "/Collection/Show A/Season 1/E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Show B - Season 2",
            FilmNameParser.pathSeasonKey("Collection", "/Collection/Show B/Season 2/E01.mkv", "E01.mkv"),
        )
    }

    @Test
    fun `detects seasons from multilingual and ordinal labels`() {
        assertEquals(2, FilmNameParser.detectedSeasonNumber("第2季"))
        assertEquals(3, FilmNameParser.detectedSeasonNumber("3rd Season"))
        assertEquals(4, FilmNameParser.detectedSeasonNumber("S04"))
        assertEquals(2, FilmNameParser.detectedSeasonNumber("Part 2"))
        assertEquals(2, FilmNameParser.detectedSeasonNumber("Cour 2"))
        assertEquals(2, FilmNameParser.detectedSeasonNumber("Temporada 2"))
        assertEquals(2, FilmNameParser.detectedSeasonNumber("Staffel 2"))
        assertEquals(2, FilmNameParser.detectedSeasonNumber("시즌 2"))
        assertEquals(2, FilmNameParser.detectedSeasonNumber("Season II"))
        assertNull(FilmNameParser.detectedSeasonNumber("2019"))
        assertNull(FilmNameParser.detectedSeasonNumber("Dear Sister"))
    }

    @Test
    fun `detects multilingual season folders`() {
        assertEquals("第2季", FilmNameParser.pathSeasonKey("Show", "/Show/第2季/E01.mkv", "E01.mkv"))
        assertEquals("시즌 2", FilmNameParser.pathSeasonKey("Show", "/Show/시즌 2/E01.mkv", "E01.mkv"))
        assertEquals("2기", FilmNameParser.pathSeasonKey("Show", "/Show/2기/E01.mkv", "E01.mkv"))
        assertEquals("Temporada 2", FilmNameParser.pathSeasonKey("Show", "/Show/Temporada 2/E01.mkv", "E01.mkv"))
        assertEquals("Season II", FilmNameParser.pathSeasonKey("Show", "/Show/Season II/E01.mkv", "E01.mkv"))
    }

    @Test
    fun `builds season keys from file names`() {        assertEquals("S2", FilmNameParser.seasonKeyFromFileName("Show S02E01.mkv"))
        assertEquals("S1", FilmNameParser.seasonKeyFromFileName("Show Season 1 - 01.mkv"))
        assertEquals("S2", FilmNameParser.seasonKeyFromFileName("Show 2nd Season - 01.mkv"))
        assertEquals("S2", FilmNameParser.seasonKeyFromFileName("Show 2x01.mkv"))
        assertEquals("SP", FilmNameParser.seasonKeyFromFileName("Show SP1.mkv"))
        assertEquals("OVA", FilmNameParser.seasonKeyFromFileName("Show OVA1.mkv"))
        assertEquals("Part 2", FilmNameParser.seasonKeyFromFileName("Show Part 2 - 01.mkv"))
        assertNull(FilmNameParser.seasonKeyFromFileName("Show E01.mkv"))
        assertEquals("Show A", FilmNameParser.seasonQualifier("Show A - Season 1"))
        assertNull(FilmNameParser.seasonQualifier("Season 1"))
    }

    @Test
    fun `ignores release quality and subtitle folders`() {
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show/1080p/E01.mkv", "E01.mkv"))
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show/Eng Sub/E01.mkv", "E01.mkv"))
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show/Complete/E01.mkv", "E01.mkv"))
    }

    @Test
    fun `accepts windows style and absolute paths`() {
        assertEquals(
            "Season 2",
            FilmNameParser.pathSeasonKey("Show", "C:\\Media\\Show\\Season 2\\E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Season 2",
            FilmNameParser.pathSeasonKey("Show", "/My Pack/Show/Season 2/E01.mkv", "E01.mkv"),
        )
    }

    @Test
    fun `keeps root files flat`() {
        assertNull(FilmNameParser.pathSeasonKey("Show", "/Show.mkv", "Show.mkv"))
        assertNull(FilmNameParser.pathSeasonKey("Show", "Show.mkv", "Show.mkv"))
    }

    @Test
    fun `matches item folders that differ only by year or quality tags`() {
        assertEquals(
            "Season 1",
            FilmNameParser.pathSeasonKey("Show (2020)", "/Show/Season 1/E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Season 1",
            FilmNameParser.pathSeasonKey("Show", "/Show [1080p]/Season 1/E01.mkv", "E01.mkv"),
        )
        assertEquals(
            "Show A - Season 1",
            FilmNameParser.pathSeasonKey("Show", "/Show A/Season 1/E01.mkv", "E01.mkv"),
        )
    }

    @Test
    fun `distinguishes special folders from titles containing the word special`() {
        assertEquals("Specials", FilmNameParser.pathSeasonKey("Show", "/Show/Specials/E01.mkv", "E01.mkv"))
        assertEquals("Special A", FilmNameParser.pathSeasonKey("Show", "/Show/Special A/E01.mkv", "E01.mkv"))
        assertFalse(FilmNameParser.isSpecialSeason("Special A"))
        assertTrue(FilmNameParser.isSpecialSeason("OVA 2"))
    }

    @Test
    fun `orders specials first and other last`() {
        val ordered = FilmNameParser.orderedSeasons(
            listOf("Other", "Dear Sister", "Season 10", "Season 2", "Specials", "Season 1"),
        )
        assertEquals(listOf("Specials", "Season 1", "Season 2", "Season 10", "Dear Sister", "Other"), ordered)
        assertEquals(
            listOf("Season 0", "Season 1"),
            FilmNameParser.orderedSeasons(listOf("Season 1", "Season 0")),
        )
    }

    @Test
    fun `deduplicates seasons case-insensitively`() {
        assertEquals(
            listOf("Season 1", "Season 2"),
            FilmNameParser.orderedSeasons(listOf("Season 1", "season 1", "Season 2")),
        )
    }

    @Test
    fun `merges equivalent season spellings but keeps parts separate`() {
        assertEquals(
            listOf("S1", "S2"),
            FilmNameParser.orderedSeasons(listOf("S1", "Season 1", "S2", "2nd Season")),
        )
        assertEquals(
            listOf("S1 Part 1", "Season 1 Part 2"),
            FilmNameParser.orderedSeasons(
                listOf("Season 1 Part 2", "S1 Part 1", "Season 1 Part 1", "S1 Part 2"),
            ),
        )
        val canonical = FilmNameParser.canonicalSeasonLabels(listOf("S1", "Season 1"))
        assertEquals("S1", canonical["Season 1"])
        assertEquals(
            listOf("SP"),
            FilmNameParser.orderedSeasons(listOf("SP", "Specials")),
        )
        assertEquals(
            listOf("OVA", "OVA 2"),
            FilmNameParser.orderedSeasons(listOf("OVA 2", "OVA")),
        )
    }

    @Test
    fun `displays parts and cours without losing them`() {
        assertEquals("Show S01", FilmNameParser.seasonDisplayTitle("Show", "Season 1"))
        assertEquals("Show S01 Part 2", FilmNameParser.seasonDisplayTitle("Show", "Season 1 Part 2"))
        assertEquals("Show Part 2", FilmNameParser.seasonDisplayTitle("Show", "Part 2"))
        assertEquals("Show Cour 2", FilmNameParser.seasonDisplayTitle("Show", "Cour 2"))
        assertEquals("Show Specials", FilmNameParser.seasonDisplayTitle("Show", "SP"))
        assertEquals("Show OVA", FilmNameParser.seasonDisplayTitle("Show", "OVA"))
        assertEquals("Show OVA 2", FilmNameParser.seasonDisplayTitle("Show", "OVA 2"))
        assertEquals("Show Specials", FilmNameParser.seasonDisplayTitle("Show", "Season 0"))
        assertEquals(
            "Collection Show A S01",
            FilmNameParser.seasonDisplayTitle("Collection", "Show A - Season 1"),
        )
        assertEquals(
            "Saiunkoku S01",
            FilmNameParser.seasonDisplayTitle("Saiunkoku", "saiunkoku (the story of saiunkoku) S01"),
        )
    }
}

class EpisodeOrderingTest {

    private fun record(
        name: String,
        season: String? = null,
        episode: Double? = null,
        end: Double? = null,
        kind: EpisodeKind = EpisodeKind.REGULAR,
        version: Int = 0,
    ) = EpisodeRecord(
        fileName = name,
        fileId = name,
        season = season,
        episode = episode,
        endEpisode = end,
        kind = kind,
        version = version,
    )

    @Test
    fun `sorts numbers semantically`() {
        val sorted = EpisodeOrdering.sortRecords(
            listOf(record("E10.mkv", episode = 10.0), record("E2.mkv", episode = 2.0), record("E1.mkv", episode = 1.0)),
        )
        assertEquals(listOf("E1.mkv", "E2.mkv", "E10.mkv"), sorted.map { it.fileName })
    }

    @Test
    fun `sorts specials before regular episodes`() {
        val sorted = EpisodeOrdering.sortRecords(
            listOf(
                record("SP1.mkv", season = "Specials", episode = 1.0, kind = EpisodeKind.SPECIAL),
                record("E01.mkv", season = "Season 1", episode = 1.0),
            ),
        )
        assertEquals(listOf("SP1.mkv", "E01.mkv"), sorted.map { it.fileName })
    }

    @Test
    fun `sorts a range before the next episode`() {
        val sorted = EpisodeOrdering.sortRecords(
            listOf(
                record("E04.mkv", episode = 4.0),
                record("E01-E03.mkv", episode = 1.0, end = 3.0),
                record("E02.mkv", episode = 2.0),
            ),
        )
        assertEquals(listOf("E01-E03.mkv", "E02.mkv", "E04.mkv"), sorted.map { it.fileName })
    }

    @Test
    fun `orders alternate releases deterministically`() {
        val sorted = EpisodeOrdering.sortRecords(
            listOf(
                record("Show E01 v2.mkv", episode = 1.0, version = 2),
                record("Show E01 v1.mkv", episode = 1.0, version = 1),
                record("Show E01.mkv", episode = 1.0),
            ),
        )
        assertEquals(
            listOf("Show E01.mkv", "Show E01 v1.mkv", "Show E01 v2.mkv"),
            sorted.map { it.fileName },
        )
    }

    @Test
    fun `numbers missing episodes contiguously`() {
        val numbered = EpisodeOrdering.numberBySeason(
            listOf(
                record("E02.mkv", episode = 2.0),
                record("E03 title.mkv", episode = null),
                record("E04.mkv", episode = 4.0),
            ),
        )
        assertEquals(listOf(2.0, 3.0, 4.0), numbered.map { it.number })
    }

    @Test
    fun `numbers each season independently`() {
        val numbered = EpisodeOrdering.numberBySeason(
            listOf(
                record("S1 E05.mkv", season = "Season 1", episode = 5.0),
                record("S1 extra.mkv", season = "Season 1", episode = null),
                record("S2 a.mkv", season = "Season 2", episode = null),
                record("S2 b.mkv", season = "Season 2", episode = null),
            ),
        )
        assertEquals(listOf(5.0, 6.0, 1.0, 2.0), numbered.map { it.number })
    }

    @Test
    fun `keeps duplicate alternate releases`() {
        val numbered = EpisodeOrdering.numberBySeason(
            listOf(
                record("a.mkv", episode = 1.0),
                record("b.mkv", episode = 1.0),
            ),
        )
        assertEquals(listOf(1.0, 1.0), numbered.map { it.number })
    }

    @Test
    fun `episode names drop release versions`() {
        val numbered = EpisodeOrdering.numberBySeason(
            listOf(
                record("Show S01E05v2.mkv", season = "Season 1", episode = 5.0, version = 2),
                record("Show S01E06v03.mkv", season = "Season 1", episode = 6.0, version = 3),
            ),
        )

        assertEquals(
            listOf("E05", "E06"),
            numbered.map { EpisodeOrdering.displayName(it) },
        )
        assertEquals(
            listOf("Season 1 E05", "Season 1 E06"),
            numbered.map { EpisodeOrdering.displayName(it, includeSeason = true) },
        )
    }

    @Test
    fun `versions still order alternate releases`() {
        val numbered = EpisodeOrdering.numberBySeason(
            listOf(
                record("Show E01 v2.mkv", episode = 1.0, version = 2),
                record("Show E01 v1.mkv", episode = 1.0, version = 1),
            ),
        )

        assertEquals(listOf("Show E01 v1.mkv", "Show E01 v2.mkv"), numbered.map { it.record.fileName })
    }

    @Test
    fun `numbers a folder without detected numbers from one`() {
        val numbered = EpisodeOrdering.numberBySeason(
            listOf(record("b.mkv"), record("a.mkv")),
        )
        assertEquals(listOf(1.0, 2.0), numbered.map { it.number })
    }

    @Test
    fun `uses metadata to fill missing numbers`() {
        val numbered = EpisodeOrdering.numberBySeason(
            listOf(record("Show - The Journey.mkv", episode = null)),
            metadataNumbers = mapOf("Show - The Journey.mkv" to 7.0),
        )
        assertEquals(listOf(7.0), numbered.map { it.number })
    }
}

class MediaClassificationTest {

    @Test
    fun `classifies catalog entries`() {
        assertEquals(MediaKind.SERIES, MediaClassifier.classifyCatalog("Show", true, false, true).kind)
        assertEquals(MediaKind.MOVIE, MediaClassifier.classifyCatalog("Show Movie", false, false, true).kind)
        assertEquals(MediaKind.OTHER, MediaClassifier.classifyCatalog("notes.txt", false, false, false).kind)
    }

    @Test
    fun `classifies folders from their contents`() {
        assertEquals(
            MediaKind.SERIES,
            MediaClassifier.classifyLibrary("Show", true, listOf("E01.mkv", "E02.mkv"), emptyList()).kind,
        )
        assertEquals(
            MediaKind.SERIES,
            MediaClassifier.classifyLibrary("Show", true, listOf("E01.mkv"), listOf("Season 1")).kind,
        )
        assertEquals(
            MediaKind.MOVIE,
            MediaClassifier.classifyLibrary("Show Movie", true, listOf("movie.mkv"), emptyList()).kind,
        )
        assertEquals(
            MediaKind.OVA,
            MediaClassifier.classifyLibrary("Show OVA", true, listOf("ova.mkv"), emptyList()).kind,
        )
        assertEquals(
            MediaKind.SPECIAL,
            MediaClassifier.classifyLibrary("Show", true, listOf("sp.mkv"), listOf("Specials")).kind,
        )
        assertEquals(
            MediaKind.SHORT,
            MediaClassifier.classifyLibrary("Show PV", true, listOf("pv.mkv"), emptyList()).kind,
        )
        assertEquals(
            MediaKind.OTHER,
            MediaClassifier.classifyLibrary("Empty", true, emptyList(), emptyList()).kind,
        )
    }
}

class LibraryAnalyzerTest {

    private fun file(name: String, path: String, id: String = path): LibraryAnalyzer.VideoFile =
        LibraryAnalyzer.VideoFile(id = id, name = name, path = path)

    @Test
    fun `analyses a nested season layout`() {
        val library = LibraryAnalyzer.analyze(
            "Show",
            listOf(
                file("E02.mkv", "/Season 1/E02.mkv"),
                file("E01.mkv", "/Season 1/E01.mkv"),
                file("E01.mkv", "/Season 2/E01.mkv"),
                file("SP1.mkv", "/Specials/SP1.mkv"),
            ),
        )

        assertEquals(MediaKind.SERIES, library.mediaKind)
        assertEquals(listOf("Specials", "Season 1", "Season 2"), library.seasons)
        assertEquals(
            listOf("Specials", "Season 1", "Season 1", "Season 2"),
            library.entries.map { it.season },
        )
    }

    @Test
    fun `infers seasons from file names in a flat folder`() {
        val library = LibraryAnalyzer.analyze(
            "Show",
            listOf(
                file("Show S01E01.mkv", "/Show S01E01.mkv"),
                file("Show S01E02.mkv", "/Show S01E02.mkv"),
                file("Show S02E01.mkv", "/Show S02E01.mkv"),
            ),
        )
        assertEquals(listOf("S1", "S2"), library.seasons)
        assertEquals(MediaKind.SERIES, library.mediaKind)
    }

    @Test
    fun `separates show prefixes in a flat folder`() {
        val library = LibraryAnalyzer.analyze(
            "Collection",
            listOf(
                file("Show A - 01.mkv", "/Show A - 01.mkv"),
                file("Show B - 01.mkv", "/Show B - 01.mkv"),
            ),
        )
        assertEquals(listOf("Show A", "Show B"), library.seasons)
        assertEquals(
            mapOf("Show A" to "Show A", "Show B" to "Show B"),
            library.entries.associate { it.season.orEmpty() to it.seriesHint },
        )
    }

    @Test
    fun `keeps a series hint for containers but not for free-form seasons`() {
        val collection = LibraryAnalyzer.analyze(
            "Collection",
            listOf(file("E01.mkv", "/Show A/E01.mkv")),
        )
        assertEquals("Show A", collection.entries.single().seriesHint)
        assertEquals("Show A", collection.seasons.single())

        val series = LibraryAnalyzer.analyze(
            "Show",
            listOf(file("E01.mkv", "/Dear Sister/E01.mkv")),
        )
        assertNull(series.entries.single().seriesHint)
        assertEquals("Dear Sister", series.seasons.single())
    }

    @Test
    fun `puts loose files in other when seasons exist`() {
        val library = LibraryAnalyzer.analyze(
            "Show",
            listOf(
                file("E01.mkv", "/Season 1/E01.mkv"),
                file("movie.mkv", "/movie.mkv"),
            ),
        )
        assertEquals(listOf("Season 1", "Other"), library.seasons)
    }

    @Test
    fun `classifies a single movie folder`() {
        val library = LibraryAnalyzer.analyze(
            "Show (2020)",
            listOf(file("Show (2020).mkv", "/Show (2020).mkv")),
        )
        assertEquals(MediaKind.MOVIE, library.mediaKind)
        assertTrue(library.isFlat)
    }

    @Test
    fun `handles a mixed movie and season folder`() {
        val library = LibraryAnalyzer.analyze(
            "Collection",
            listOf(
                file("E01.mkv", "/Show A/Season 1/E01.mkv"),
                file("E02.mkv", "/Show A/Season 1/E02.mkv"),
                file("Movie.mkv", "/Movie.mkv"),
            ),
        )
        assertEquals(listOf("Show A - Season 1", "Other"), library.seasons)
        assertEquals(MediaKind.SERIES, library.mediaKind)
        assertEquals("Show A - Season 1", library.entries.first().season)
    }

    @Test
    fun `folder context wins over a conflicting file season`() {
        val library = LibraryAnalyzer.analyze(
            "Show",
            listOf(file("Show S02E01.mkv", "/Season 1/Show S02E01.mkv")),
        )
        assertEquals(listOf("Season 1"), library.seasons)
        assertEquals(1.0, library.entries.single().episode ?: 0.0, 0.0)
    }

    @Test
    fun `merges equivalent season labels from folders and files`() {
        val library = LibraryAnalyzer.analyze(
            "Show",
            listOf(
                file("Show S01E01.mkv", "/Show S01E01.mkv"),
                file("E02.mkv", "/Season 1/E02.mkv"),
            ),
        )
        assertEquals(listOf("S1"), library.seasons)
        assertEquals(listOf("S1", "S1"), library.entries.map { it.season })
    }

    @Test
    fun `classifies a single root file as a movie`() {
        val library = LibraryAnalyzer.analyze(
            "Show (2020).mkv",
            listOf(file("Show (2020).mkv", "/Show (2020).mkv")),
            isFolder = false,
        )
        assertEquals(MediaKind.MOVIE, library.mediaKind)
        assertFalse(library.isFolder)
    }

    @Test
    fun `produces deterministic final episode output`() {
        val library = LibraryAnalyzer.analyze(
            "Show",
            listOf(
                file("E02.mkv", "/Season 1/E02.mkv"),
                file("E01.mkv", "/Season 1/E01.mkv"),
                file("E01.mkv", "/Season 2/E01.mkv"),
                file("SP1.mkv", "/Specials/SP1.mkv"),
            ),
        )
        val numbered = EpisodeOrdering.numberBySeason(library.entries.map { it.toRecord() })
        assertEquals(
            listOf("Specials SP01", "Season 1 E01", "Season 1 E02", "Season 2 E01"),
            numbered.map { EpisodeOrdering.displayName(it, includeSeason = true) },
        )
        assertEquals(listOf(1.0, 1.0, 2.0, 1.0), numbered.map { it.number })
    }

    @Test
    fun `deduplicates the same file id`() {
        val library = LibraryAnalyzer.analyze(
            "Show",
            listOf(
                file("E01.mkv", "/E01.mkv", id = "same"),
                file("E01 copy.mkv", "/E01 copy.mkv", id = "same"),
            ),
        )
        assertEquals(1, library.entries.size)
    }

    @Test
    fun `handles a large flat library without losing entries`() {
        val files = (1..2000).map { index ->
            file("Show - %04d.mkv".format(index), "/Show - %04d.mkv".format(index), id = "id$index")
        }
        val library = LibraryAnalyzer.analyze("Show", files)
        assertEquals(2000, library.entries.size)
        assertEquals(MediaKind.SERIES, library.mediaKind)
    }

    @Test
    fun `handles a large mixed season library`() {
        val files = (1..5000).map { index ->
            val season = (index - 1) / 50 + 1
            val episode = (index - 1) % 50 + 1
            file(
                "Show S%02dE%02d.mkv".format(season, episode),
                "/Season $season/Show S%02dE%02d.mkv".format(season, episode),
                id = "id$index",
            )
        }
        val library = LibraryAnalyzer.analyze("Show", files)
        assertEquals(5000, library.entries.size)
        assertEquals(100, library.seasons.size)
        assertEquals(MediaKind.SERIES, library.mediaKind)
    }
}

class MetadataMatchingTest {

    private fun candidate(
        key: String,
        title: String,
        year: Int? = null,
        format: String? = null,
        adult: Boolean = false,
        popularity: Double = 0.0,
    ) = MetadataCandidate(
        key = key,
        titles = listOf(title),
        year = year,
        format = format,
        isAdult = adult,
        popularity = popularity,
        value = title,
    )

    @Test
    fun `ranks the exact title above a sequel`() {
        val ranked = MatchScoring.rank(
            query = "Show",
            year = 2020,
            preferredFormat = "TV",
            candidates = listOf(
                candidate("sequel", "Show Season 2", 2022, "TV"),
                candidate("first", "Show", 2020, "TV"),
            ),
        )
        assertEquals("first", ranked.first().candidate.key)
    }

    @Test
    fun `uses the season hint to choose the right entry`() {
        val ranked = MatchScoring.rank(
            query = "Show Season 2",
            year = 2022,
            preferredFormat = "TV",
            seasonNumber = 2,
            candidates = listOf(
                candidate("first", "Show", 2020, "TV"),
                candidate("second", "Show 2nd Season", 2022, "TV"),
            ),
        )
        assertEquals("second", ranked.first().candidate.key)
    }

    @Test
    fun `rejects the first season when a later season was requested`() {
        val ranked = MatchScoring.rank(
            query = "Show Season 2",
            // The folder year usually comes from the parent and can be the first season's year.
            year = 2020,
            preferredFormat = "TV",
            seasonNumber = 2,
            candidates = listOf(
                MetadataCandidate(
                    key = "first",
                    titles = listOf("Show"),
                    year = 2020,
                    format = "TV",
                    seasonNumber = 1,
                    value = "first",
                ),
                MetadataCandidate(
                    key = "second",
                    titles = listOf("Show 2nd Season"),
                    year = 2022,
                    format = "TV",
                    seasonNumber = 2,
                    value = "second",
                ),
            ),
        )
        assertEquals("second", ranked.first().candidate.key)
    }

    @Test
    fun `detects the season carried by provider titles`() {
        assertEquals(2, MatchScoring.titleSeason(listOf("Show Season 2")))
        assertEquals(3, MatchScoring.titleSeason(listOf("Show 3rd Season")))
        assertEquals(2, MatchScoring.titleSeason(listOf("Show Part 2")))
        assertEquals(2, MatchScoring.titleSeason(listOf("Show II")))
        assertEquals(0, MatchScoring.titleSeason(listOf("Show Specials")))
    }

    @Test
    fun `prefers the requested media format`() {
        val ranked = MatchScoring.rank(
            query = "Show",
            year = 2020,
            preferredFormat = "MOVIE",
            candidates = listOf(
                candidate("tv", "Show", 2020, "TV"),
                candidate("movie", "Show", 2020, "MOVIE"),
            ),
        )
        assertEquals("movie", ranked.first().candidate.key)
    }

    @Test
    fun `rejects unrelated weak candidates`() {
        assertTrue(
            MatchScoring.rank(
                query = "Monster",
                candidates = listOf(candidate("mob", "Mob Psycho 100")),
            ).isEmpty(),
        )
        assertTrue(
            MatchScoring.rank(
                query = "One Piece",
                candidates = listOf(candidate("opm", "One Punch Man")),
            ).isEmpty(),
        )
    }

    @Test
    fun `rejects adult and year-incompatible results`() {
        assertTrue(
            MatchScoring.rank(
                query = "Show",
                candidates = listOf(candidate("adult", "Show", adult = true)),
            ).isEmpty(),
        )
        assertTrue(
            MatchScoring.rank(
                query = "Show",
                year = 2020,
                candidates = listOf(candidate("old", "Show", 2010)),
            ).isEmpty(),
        )
    }

    @Test
    fun `matches accents punctuation and aliases`() {
        assertTrue(MatchScoring.titleMatches("Pokemon", "Pokémon"))
        assertTrue(MatchScoring.titleMatches("Fate Stay Night", "Fate/stay night"))
        assertTrue(MatchScoring.titleMatches("Show Season 2", "Show"))
        assertTrue(MatchScoring.titleMatches("Show", "The Show"))
        assertFalse(MatchScoring.titleMatches("Monster", "Mob Psycho 100"))
    }

    @Test
    fun `is plausible rejects empty titles`() {
        assertFalse(MatchScoring.isPlausible("Show", null, emptyList(), null, isAdult = false))
        assertTrue(MatchScoring.isPlausible("Show", 2020, listOf("Show"), 2021, isAdult = false))
    }
}

class CatalogProbeTest {

    @Test
    fun `opens seasons for season like folders`() {
        assertTrue(CatalogProbe.needsSeasonScreen(listOf("Season 1", "Season 2"), emptyList()))
        assertTrue(CatalogProbe.needsSeasonScreen(listOf("Specials"), emptyList()))
        assertTrue(CatalogProbe.needsSeasonScreen(listOf("Show A", "Show B"), emptyList()))
    }

    @Test
    fun `keeps discs quality and subtitle folders flat`() {
        assertFalse(CatalogProbe.needsSeasonScreen(listOf("Disc 1", "Disc 2"), emptyList()))
        assertFalse(CatalogProbe.needsSeasonScreen(listOf("1080p", "Subs"), emptyList()))
        assertFalse(CatalogProbe.needsSeasonScreen(listOf("Videos"), emptyList()))
    }

    @Test
    fun `detects seasons carried by file names`() {
        assertTrue(
            CatalogProbe.needsSeasonScreen(
                emptyList(),
                listOf("Show S01E01.mkv", "Show S02E01.mkv"),
            ),
        )
        assertFalse(
            CatalogProbe.needsSeasonScreen(
                emptyList(),
                listOf("Show S01E01.mkv", "Show S01E02.mkv"),
            ),
        )
        assertFalse(CatalogProbe.needsSeasonScreen(emptyList(), listOf("Show - 01.mkv", "Show - 02.mkv")))
    }
}

class CatalogSearchTest {

    private fun item(id: String, name: String) = CatalogItem(
        id = id,
        name = name,
        isFolder = true,
        isTorrent = false,
    )

    @Test
    fun `sorts catalog names naturally`() {
        val sorted = CatalogFilters.apply(
            listOf(item("10", "Show E10"), item("2", "Show E2")),
            sortIndex = CatalogFilters.SORT_NAME_ASC,
        )
        assertEquals(listOf("2", "10"), sorted.map { it.id })
    }

    @Test
    fun `search matches cleaned titles and tokens`() {
        val items = listOf(
            item("1", "[Group] Horimiya [1080p]"),
            item("2", "Kaguya-sama: Love is War"),
        )
        assertEquals(listOf("1"), CatalogFilters.search(items, "horimiya").map { it.id })
        assertEquals(listOf("2"), CatalogFilters.search(items, "kaguya love").map { it.id })
        assertEquals(listOf("2"), CatalogFilters.search(items, "kaguya-sama").map { it.id })
    }

    @Test
    fun `search folds accents`() {
        val items = listOf(item("1", "Pokémon"))
        assertEquals(listOf("1"), CatalogFilters.search(items, "pokemon").map { it.id })
    }
}

class DateAndExpiryTest {

    @Test
    fun `parses date only timestamps`() {
        assertEquals(1_704_067_200_000L, FilmNameParser.parseDate("2024-01-01"))
    }

    @Test
    fun `treats zero expiry as no expiry`() {
        assertNull(FilmNameParser.parseExpiryMillis("0"))
        assertNull(FilmNameParser.parseExpiryMillis("-1"))
        assertEquals(1_704_067_200_000L, FilmNameParser.parseExpiryMillis("2024-01-01"))
    }
}

class AniListDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses the ranked AniList page shape`() {
        val body = """
            {"data":{"Page":{"media":[{
              "id":124080,
              "title":{"romaji":"Horimiya","english":"Horimiya","native":"ホリミヤ"},
              "synonyms":["堀与宫村"],
              "format":"TV",
              "seasonYear":2021,
              "startDate":{"year":2021,"month":1,"day":10},
              "popularity":556347,
              "averageScore":81,
              "isAdult":false,
              "description":"A secret life.",
              "coverImage":{"extraLarge":"https://example/cover.jpg","large":"https://example/cover-l.jpg"},
              "bannerImage":"https://example/banner.jpg",
              "genres":["Comedy","Romance"],
              "status":"FINISHED",
              "episodes":13,
              "studios":{"edges":[{"isMain":true,"node":{"name":"CloverWorks"}}]},
              "characters":{"edges":[{"role":"MAIN","node":{"name":{"full":"Kyouko Hori"}}}]},
              "recommendations":{"nodes":[]},
              "relations":{"edges":[]},
              "airingSchedule":{"nodes":[{"episode":1,"airingAt":1600000000}]},
              "streamingEpisodes":[{"title":"Episode 1 - The Start","thumbnail":null,"url":null,"site":"Crunchyroll"}]
            }]}}}
        """.trimIndent()

        val parsed = json.decodeFromString<AniListPageResponse>(body)
        val media = parsed.data?.Page?.media?.single()

        assertEquals(124080, media?.id)
        assertEquals("Horimiya", media?.title?.romaji)
        assertEquals(2021, media?.seasonYear)
        assertEquals("TV", media?.format)
        assertEquals("CloverWorks", media?.studios?.edges?.single()?.node?.name)
        assertEquals("Kyouko Hori", media?.characters?.edges?.single()?.node?.name?.full)
        assertEquals("Episode 1 - The Start", media?.streamingEpisodes?.single()?.title)
    }
}

class MetadataEpisodeTest {

    @Test
    fun `resolves an episode from a known title`() {
        val metadata = AnimeMetadata(
            episodes = listOf(
                EpisodeInfo(5, "The Journey"),
                EpisodeInfo(6, "A New Hope"),
            ),
        )
        assertEquals(5.0, metadata.resolveEpisodeValue("Show - The Journey.mkv") ?: 0.0, 0.0)
        assertEquals(6.0, metadata.resolveEpisodeValue("Show - A New Hope.mkv") ?: 0.0, 0.0)
    }

    @Test
    fun `prefers an explicit number in the file name`() {
        val metadata = AnimeMetadata(episodes = listOf(EpisodeInfo(5, "The Journey")))
        assertEquals(7.0, metadata.resolveEpisodeValue("Show - 07.mkv") ?: 0.0, 0.0)
    }

    @Test
    fun `ignores very short episode titles`() {
        val metadata = AnimeMetadata(episodes = listOf(EpisodeInfo(1, "A")))
        assertNull(metadata.resolveEpisodeValue("Show - A.mkv"))
    }
}
