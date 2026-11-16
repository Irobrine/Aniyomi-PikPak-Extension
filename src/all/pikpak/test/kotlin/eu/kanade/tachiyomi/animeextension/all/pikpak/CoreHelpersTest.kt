package eu.kanade.tachiyomi.animeextension.all.pikpak

import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCloudDownloadRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCloudDownloadUrl
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakMoveRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakMoveTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtlCacheTest {

    @Test
    fun `returns a value until it expires`() {
        val cache = TtlCache<String, Int>()
        cache.put("a", 1, ttlMs = 1_000, now = 10_000)

        assertEquals(1, cache.get("a", now = 10_500))
        assertNull(cache.get("a", now = 11_000))
    }

    @Test
    fun `drops expired entries`() {
        val cache = TtlCache<String, Int>()
        cache.put("a", 1, ttlMs = 1_000, now = 0)

        assertNull(cache.get("a", now = 5_000))
        assertEquals(0, cache.size())
    }

    @Test
    fun `never stores non-positive ttls`() {
        val cache = TtlCache<String, Int>()
        cache.put("a", 1, ttlMs = 0, now = 0)
        cache.put("b", 2, ttlMs = -5, now = 0)

        assertEquals(0, cache.size())
    }

    @Test
    fun `evicts the least recently used entry past the cap`() {
        val cache = TtlCache<String, Int>(maxSize = 2)
        cache.put("a", 1, ttlMs = 10_000, now = 0)
        cache.put("b", 2, ttlMs = 10_000, now = 0)

        // Touch "a" so "b" becomes the least recently used entry.
        assertEquals(1, cache.get("a", now = 1))

        cache.put("c", 3, ttlMs = 10_000, now = 2)

        assertEquals(2, cache.size())
        assertEquals(1, cache.get("a", now = 3))
        assertNull(cache.get("b", now = 3))
        assertEquals(3, cache.get("c", now = 3))
    }

    @Test
    fun `clear removes everything`() {
        val cache = TtlCache<String, Int>()
        cache.put("a", 1, ttlMs = 10_000)
        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }
}

class MatchScoringTest {

    @Test
    fun `accepts an exact title`() {
        assertTrue(MatchScoring.titleMatches("Horimiya", "Horimiya"))
    }

    @Test
    fun `accepts a candidate that contains the query`() {
        assertTrue(MatchScoring.titleMatches("Fate", "Fate/stay night"))
        assertTrue(MatchScoring.titleMatches("Show Season 2", "Show"))
    }

    @Test
    fun `rejects an unrelated title`() {
        assertFalse(MatchScoring.titleMatches("Monster", "Mob Psycho 100"))
        assertFalse(MatchScoring.titleMatches("One Piece", "One Punch Man"))
    }

    @Test
    fun `accepts sequels and season queries as word matches`() {
        assertTrue(MatchScoring.titleMatches("Bleach", "Bleach: Thousand-Year Blood War"))
        assertTrue(MatchScoring.titleMatches("Show Season 2", "Show"))
    }

    @Test
    fun `tolerates one year of difference and rejects more`() {
        assertTrue(MatchScoring.isPlausible("Show", 2020, listOf("Show"), 2021, isAdult = false))
        assertTrue(MatchScoring.isPlausible("Show", 2020, listOf("Show"), 2019, isAdult = false))
        assertFalse(MatchScoring.isPlausible("Show", 2020, listOf("Show"), 2015, isAdult = false))
    }

    @Test
    fun `ignores a missing year`() {
        assertTrue(MatchScoring.isPlausible("Show", null, listOf("Show"), 1999, isAdult = false))
        assertTrue(MatchScoring.isPlausible("Show", 2020, listOf("Show"), null, isAdult = false))
    }

    @Test
    fun `rejects adult results`() {
        assertFalse(MatchScoring.isPlausible("Show", null, listOf("Show"), null, isAdult = true))
    }

    @Test
    fun `normalises punctuation and case`() {
        assertEquals("fatestaynight", MatchScoring.normalize("Fate/stay-night"))
        assertEquals("smorrebrod", MediaText.compactKey("Smørrebrød"))
        assertTrue(MatchScoring.titleMatches("Smorrebrod", "Smørrebrød"))
    }
}

class CatalogFiltersTest {

    private fun item(
        id: String,
        name: String,
        created: String? = null,
        folder: Boolean = true,
        pinned: Boolean = false,
        order: Int = Int.MAX_VALUE,
    ) = CatalogItem(
        id = id,
        name = name,
        isFolder = folder,
        isTorrent = false,
        isPinned = pinned,
        createdTime = created,
        order = order,
    )

    @Test
    fun `sorts newest first by default`() {
        val sorted = CatalogFilters.apply(
            listOf(
                item("1", "Old", "2020-01-01T00:00:00"),
                item("2", "New", "2024-01-01T00:00:00"),
            ),
        )
        assertEquals(listOf("2", "1"), sorted.map { it.id })
    }

    @Test
    fun `sorts oldest first`() {
        val sorted = CatalogFilters.apply(
            listOf(
                item("1", "Old", "2020-01-01T00:00:00"),
                item("2", "New", "2024-01-01T00:00:00"),
            ),
            sortIndex = CatalogFilters.SORT_OLDEST,
        )
        assertEquals(listOf("1", "2"), sorted.map { it.id })
    }

    @Test
    fun `sorts by name in both directions`() {
        val items = listOf(item("1", "beta"), item("2", "Alpha"), item("3", "Gamma"))

        assertEquals(
            listOf("2", "1", "3"),
            CatalogFilters.apply(items, sortIndex = CatalogFilters.SORT_NAME_ASC).map { it.id },
        )
        assertEquals(
            listOf("3", "1", "2"),
            CatalogFilters.apply(items, sortIndex = CatalogFilters.SORT_NAME_DESC).map { it.id },
        )
    }

    @Test
    fun `keeps pinned folders first in their configured order`() {
        val sorted = CatalogFilters.apply(
            listOf(
                item("a", "Zeta", pinned = true, order = 1),
                item("b", "Alpha", pinned = true, order = 0),
                item("c", "Beta"),
            ),
            sortIndex = CatalogFilters.SORT_NAME_ASC,
        )
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun `filters by type`() {
        val items = listOf(item("f", "Folder"), item("s", "File", folder = false))

        assertEquals(
            listOf("f"),
            CatalogFilters.apply(items, typeIndex = CatalogFilters.TYPE_FOLDERS).map { it.id },
        )
        assertEquals(
            listOf("s"),
            CatalogFilters.apply(items, typeIndex = CatalogFilters.TYPE_FILES).map { it.id },
        )
    }

    @Test
    fun `search matches cleaned titles case-insensitively`() {
        val items = listOf(
            item("1", "[Group] Horimiya [1080p]"),
            item("2", "Other Show"),
        )

        assertEquals(listOf("1"), CatalogFilters.search(items, "horimiya").map { it.id })
        assertEquals(2, CatalogFilters.search(items, "").size)
    }

    @Test
    fun `reads filter state with fallbacks`() {
        val filters = CatalogFilters.newFilterList()
        assertEquals(CatalogFilters.SORT_NEWEST, CatalogFilters.sortIndex(filters))
        assertEquals(CatalogFilters.TYPE_ALL, CatalogFilters.typeIndex(filters))
        assertEquals(CatalogFilters.SORT_NAME_ASC, CatalogFilters.sortIndex(null, CatalogFilters.SORT_NAME_ASC))
    }
}

class PikPakCloudDownloadRequestTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `sends the chosen folder with an empty folder type`() {
        val body = json.encodeToString(
            PikPakCloudDownloadRequest(
                url = PikPakCloudDownloadUrl("magnet:?xt=urn:btih:abc"),
                parentId = "folder-id",
            ),
        )

        assertTrue(body.contains("\"parent_id\":\"folder-id\""))
        assertTrue(body.contains("\"folder_type\":\"\""))
        assertFalse(body.contains("DOWNLOAD"))
    }

    @Test
    fun `sends the move target folder with the download id`() {
        val body = json.encodeToString(
            PikPakMoveRequest(
                ids = listOf("file-id"),
                to = PikPakMoveTarget(parentId = "target-folder"),
            ),
        )

        assertTrue(body.contains("\"ids\":[\"file-id\"]"))
        assertTrue(body.contains("\"to\":{\"parent_id\":\"target-folder\"}"))
    }
}

class PikPakPrefsTest {
    @Test
    fun `parses newline and semicolon separated folders`() {
        assertEquals(
            listOf("My Shared/Anime", "My Pack/Shows", "id123"),
            PikPakPrefs.parseExtraFolders("My Shared/Anime\nMy Pack/Shows; id123"),
        )
    }

    @Test
    fun `keeps commas inside names`() {
        assertEquals(
            listOf("Shows, Anime, and More", "Other"),
            PikPakPrefs.parseExtraFolders("Shows, Anime, and More\nOther"),
        )
        assertEquals(
            listOf("Shows, Anime, and More"),
            PikPakPrefs.parseExtraFolders("Shows, Anime, and More"),
        )
    }

    @Test
    fun `splits legacy comma separated values`() {
        assertEquals(
            listOf("One", "Two"),
            PikPakPrefs.splitLegacyExtraFolders("One, Two"),
        )
    }

    @Test
    fun `ignores blank entries and duplicates`() {
        assertEquals(listOf("A", "B"), PikPakPrefs.parseExtraFolders("\n A \n\n B \nA\n"))
        assertTrue(PikPakPrefs.parseExtraFolders(null).isEmpty())
        assertTrue(PikPakPrefs.parseExtraFolders("   ").isEmpty())
    }

    @Test
    fun `formats durations`() {
        assertEquals("2h 5m", PikPakPrefs.formatDuration(7_500))
        assertEquals("5m", PikPakPrefs.formatDuration(300))
        assertEquals("42s", PikPakPrefs.formatDuration(42))
        assertEquals("0s", PikPakPrefs.formatDuration(-1))
    }

    @Test
    fun `extracts refresh tokens from storage values`() {
        assertEquals("abc", PikPakPrefs.parseRefreshToken("""{"refresh_token":"abc"}"""))
        assertEquals("abc", PikPakPrefs.parseRefreshToken("refresh_token=abc&expires=1"))
        assertEquals("plain-token-value-1234", PikPakPrefs.parseRefreshToken("plain-token-value-1234"))
        assertNull(PikPakPrefs.parseRefreshToken("short"))
    }

    @Test
    fun `decodes url encoded storage values`() {
        assertEquals("""{"refresh_token":"abc"}""", PikPakPrefs.decodeWebValue("%7B%22refresh_token%22%3A%22abc%22%7D"))
        assertEquals("plain", PikPakPrefs.decodeWebValue("plain"))
    }

    @Test
    fun `splits cloud links one per line`() {
        assertEquals(
            listOf("magnet:?xt=urn:btih:abc", "https://youtu.be/xyz", "https://example.com/a.mp4"),
            PikPakPrefs.parseCloudLinks(
                "magnet:?xt=urn:btih:abc\n\n https://youtu.be/xyz \r\nhttps://example.com/a.mp4\n" +
                    "magnet:?xt=urn:btih:abc",
            ),
        )
        assertTrue(PikPakPrefs.parseCloudLinks(null).isEmpty())
        assertTrue(PikPakPrefs.parseCloudLinks("  \n ").isEmpty())
    }

    @Test
    fun `labels cloud links until pikpak reports a name`() {
        assertEquals(
            "Show 01",
            PikPakPrefs.cloudLinkLabel("magnet:?xt=urn:btih:abc&dn=Show%2001"),
        )
        assertNull(PikPakPrefs.cloudLinkLabel("magnet:?xt=urn:btih:abc"))
        assertEquals("youtu.be/xyz", PikPakPrefs.cloudLinkLabel("https://youtu.be/xyz"))
    }
}

class TokenCipherTest {

    @Test
    fun `encodes and decodes the envelope`() {
        val iv = ByteArray(12) { it.toByte() }
        val payload = "session value".toByteArray()

        val encoded = TokenCipher.encode(iv, payload)
        assertTrue(TokenCipher.isEncrypted(encoded))

        val (decodedIv, decodedPayload) = TokenCipher.decodePayload(encoded)!!
        assertEquals(iv.toList(), decodedIv.toList())
        assertEquals(payload.toList(), decodedPayload.toList())
    }

    @Test
    fun `recognises legacy plain values`() {
        assertFalse(TokenCipher.isEncrypted("plain-token"))
        assertNull(TokenCipher.decodePayload("plain-token"))
    }

    @Test
    fun `rejects malformed envelopes`() {
        assertNull(TokenCipher.decodePayload("v1:"))
        assertNull(TokenCipher.decodePayload("v1:nocolon"))
        assertNull(TokenCipher.decodePayload("v1:!!!:!!!"))
    }
}

class QualitySelectorTest {

    private fun variant(title: String, origin: Boolean = false, default: Boolean = false) =
        PikPakClient.StreamVariant(
            url = "https://cdn/$title",
            title = title,
            isOrigin = origin,
            isDefault = default,
        )

    private fun List<PikPakClient.StreamVariant>.titles() = map { it.title }

    @Test
    fun `automatic keeps every stream`() {
        val variants = listOf(variant("1080p"), variant("720p"), variant("480p"))
        assertEquals(variants, QualitySelector.select(variants, QualitySelector.AUTO))
        assertEquals(variants, QualitySelector.select(variants, null))
        assertEquals(variants, QualitySelector.select(variants, ""))
    }

    @Test
    fun `picks the exact resolution when present`() {
        val variants = listOf(variant("1080p"), variant("720p"), variant("480p"))
        assertEquals(listOf("720p"), QualitySelector.select(variants, "720p").titles())
    }

    @Test
    fun `prefers the closest higher resolution before lower ones`() {
        val variants = listOf(variant("480p"), variant("2160p"), variant("1080p"))
        assertEquals(listOf("1080p"), QualitySelector.select(variants, "720p").titles())
    }

    @Test
    fun `uses higher resolutions in ascending order`() {
        val ordered = QualitySelector.order(
            listOf(variant("2160p"), variant("1440p"), variant("480p")),
            "720p",
        ).titles()
        assertEquals(listOf("1440p", "2160p", "480p"), ordered)
    }

    @Test
    fun `falls back to the closest lower resolution`() {
        val variants = listOf(variant("480p"), variant("360p"))
        assertEquals(listOf("480p"), QualitySelector.select(variants, "1080p").titles())
    }

    @Test
    fun `original selects the origin stream`() {
        val variants = listOf(variant("1080p"), variant("Original", origin = true))
        assertEquals(listOf("Original"), QualitySelector.select(variants, QualitySelector.ORIGIN).titles())
    }

    @Test
    fun `unlabelled original ranks above named resolutions`() {
        val variants = listOf(variant("720p"), variant("Original", origin = true))
        assertEquals(listOf("Original"), QualitySelector.select(variants, "1080p").titles())
    }

    @Test
    fun `unlabelled non-origin streams go last`() {
        val ordered = QualitySelector.order(
            listOf(variant("PikPak"), variant("240p")),
            "360p",
        ).titles()
        assertEquals(listOf("240p", "PikPak"), ordered)
    }
}
