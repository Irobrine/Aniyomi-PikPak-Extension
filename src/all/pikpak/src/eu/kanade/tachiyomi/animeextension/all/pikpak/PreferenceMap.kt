package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.content.SharedPreferences

/**
 * A small string map kept in the source's preferences.
 *
 * The catalog uses it to remember drive ids and folder layouts between app processes. Without
 * them a cold start walks down from the drive root and probes every folder on the page again,
 * which costs a drive request and a full round trip for each one.
 *
 * Entries are stored as `key<TAB>value<TAB>written_at` lines, bounded in size and pruned by age.
 * Keys are folder ids and configured paths, so tabs and newlines are flattened to spaces.
 */
internal class PreferenceMap(
    private val preferences: SharedPreferences,
    private val preferenceKey: String,
    private val ttlMs: Long = NO_EXPIRY,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    @Synchronized
    fun get(key: String, now: Long = System.currentTimeMillis()): String? {
        val entry = read().entries.firstOrNull { it.key == key } ?: return null
        if (ttlMs != NO_EXPIRY && now - entry.writtenAt > ttlMs) {
            remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: String, value: String, now: Long = System.currentTimeMillis()) {
        val normalizedKey = normalize(key)
        if (normalizedKey.isEmpty()) return

        val kept = read().entries
            .filterNot { it.key == normalizedKey }
            .takeLast(maxEntries - 1)
        write(kept + Entry(normalizedKey, normalize(value), now))
    }

    @Synchronized
    fun remove(key: String) {
        val normalizedKey = normalize(key)
        val remaining = read().entries.filterNot { it.key == normalizedKey }
        write(remaining)
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(preferenceKey).apply()
    }

    private fun read(): Stored {
        val raw = preferences.getString(preferenceKey, "").orEmpty()
        if (raw.isEmpty()) return Stored(emptyList())

        val entries = raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(SEPARATOR)
                if (parts.size != 3) return@mapNotNull null
                Entry(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
            }
            .toList()
        return Stored(entries)
    }

    private fun write(entries: List<Entry>) {
        val value = entries.joinToString("\n") { "${it.key}$SEPARATOR${it.value}$SEPARATOR${it.writtenAt}" }
        preferences.edit().putString(preferenceKey, value).apply()
    }

    private fun normalize(value: String): String = value.replace(SEPARATOR, ' ').replace('\n', ' ').trim()

    private class Entry(val key: String, val value: String, val writtenAt: Long)

    private class Stored(val entries: List<Entry>)

    companion object {
        const val NO_EXPIRY = -1L
        private const val MAX_ENTRIES = 400
        private const val SEPARATOR = '\t'
    }
}
