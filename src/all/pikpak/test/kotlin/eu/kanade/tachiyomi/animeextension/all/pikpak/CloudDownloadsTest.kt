package eu.kanade.tachiyomi.animeextension.all.pikpak

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDownloadRecordTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `decodes records saved before queued edits existed`() {
        val raw = """
            [{"taskId":"task","fileId":"file","name":"Show","url":"magnet:?xt=urn:btih:1",
              "destination":"My Pack","phase":"PHASE_TYPE_RUNNING","progress":42,"updatedAt":1}]
        """.trimIndent()

        val record = json.decodeFromString<List<PikPakCloudDownloads.Record>>(raw).single()

        assertEquals("Show", record.name)
        assertEquals(42, record.percent)
        assertNull(record.pendingName)
        assertFalse(record.hasPendingEdit)
    }

    @Test
    fun `round trips a queued rename`() {
        val record = PikPakCloudDownloads.Record(
            taskId = "task",
            url = "magnet:?xt=urn:btih:1",
            phase = PikPakCloudDownloads.PHASE_RUNNING,
            pendingName = "New name",
            updatedAt = 2,
        )

        val decoded = json.decodeFromString<PikPakCloudDownloads.Record>(json.encodeToString(record))

        assertEquals("New name", decoded.pendingName)
        assertTrue(decoded.hasPendingEdit)
        assertFalse(decoded.finished)
    }

    @Test
    fun `a failed record has no pending edit to apply`() {
        val record = PikPakCloudDownloads.Record(
            phase = PikPakCloudDownloads.PHASE_ERROR,
            pendingName = "New name",
        )

        assertTrue(record.finished)
        assertTrue(record.hasPendingEdit)
    }
}

class CloudEntryParsingTest {

    @Test
    fun `pairs each link with the name on the same line`() {
        val entries = PikPakPrefs.parseCloudEntries(
            links = "magnet:?xt=urn:btih:1\nmagnet:?xt=urn:btih:2",
            names = "Show A\nShow B",
        )

        assertEquals(listOf("magnet:?xt=urn:btih:1", "magnet:?xt=urn:btih:2"), entries.map { it.first })
        assertEquals(listOf("Show A", "Show B"), entries.map { it.second })
    }

    @Test
    fun `a blank name line keeps the original name`() {
        val entries = PikPakPrefs.parseCloudEntries(
            links = "l1\nl2\nl3",
            names = "Show A\n\nShow C",
        )

        assertEquals(listOf("Show A", null, "Show C"), entries.map { it.second })
    }

    @Test
    fun `missing name lines fall back to the original names`() {
        val entries = PikPakPrefs.parseCloudEntries(
            links = "l1\nl2\nl3",
            names = "Show A",
        )

        assertEquals(listOf("Show A", null, null), entries.map { it.second })
    }

    @Test
    fun `blank link lines are skipped while names keep their lines`() {
        val entries = PikPakPrefs.parseCloudEntries(
            links = "l1\n\nl3",
            names = "Show A\n\nShow C",
        )

        assertEquals(listOf("l1", "l3"), entries.map { it.first })
        assertEquals(listOf("Show A", "Show C"), entries.map { it.second })
    }

    @Test
    fun `handles windows line endings and whitespace`() {
        val entries = PikPakPrefs.parseCloudEntries(
            links = "  l1  \r\n\r\n l2 \r\n",
            names = " Show A \r\n \r\n Show B \r\n",
        )

        assertEquals(listOf("l1", "l2"), entries.map { it.first })
        assertEquals(listOf("Show A", "Show B"), entries.map { it.second })
    }

    @Test
    fun `ignores names without links`() {
        val entries = PikPakPrefs.parseCloudEntries(
            links = "l1",
            names = "Show A\nShow B\nShow C",
        )

        assertEquals(1, entries.size)
        assertEquals("Show A", entries.single().second)
    }

    @Test
    fun `no names leaves every download with its original name`() {
        val entries = PikPakPrefs.parseCloudEntries(links = "l1\nl2", names = null)

        assertEquals(listOf(null, null), entries.map { it.second })
    }
}
