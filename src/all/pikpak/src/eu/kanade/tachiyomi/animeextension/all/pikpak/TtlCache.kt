package eu.kanade.tachiyomi.animeextension.all.pikpak

/**
 * Small thread-safe cache with per-entry expiry and a hard size cap.
 *
 * Entries are evicted least-recently-used first once [maxSize] is reached, so long sessions on
 * large drives cannot grow the cache without bound.
 */
internal class TtlCache<K, V>(private val maxSize: Int = 256) {
    private class Entry<V>(val value: V, val expiresAt: Long)

    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K, now: Long = System.currentTimeMillis()): V? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= now) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V, ttlMs: Long, now: Long = System.currentTimeMillis()) {
        if (ttlMs <= 0L) return
        entries[key] = Entry(value, now + ttlMs)
    }

    @Synchronized
    fun remove(key: K) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
