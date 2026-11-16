package eu.kanade.tachiyomi.animeextension.all.pikpak

/** Preference keys and small pure helpers shared by the source and its settings screen. */
internal object PikPakPrefs {
    const val SESSION_SEED = "pikpak_refresh_token_input"
    const val DEVICE_ID = "pikpak_device_id"
    const val EXTRA_FOLDERS = "pikpak_extra_folders"
    const val EXTRA_FOLDERS_LINES = "pikpak_extra_folders_lines"

    const val ACCESS_TOKEN = "pikpak_access_token"
    const val REFRESH_TOKEN = "pikpak_refresh_token"
    const val USER_ID = "pikpak_user_id"
    const val TOKEN_EXPIRES_AT = "pikpak_token_expires_at"
    const val TOKEN_CLIENT = "pikpak_token_client"
    const val TOKEN_STORE_VERSION = "web-v1"

    const val TORRENTS = "show_torrents"
    const val WEB_DOWNLOADS = "show_web_downloads"
    const val METADATA = "fetch_metadata"
    const val TMDB_KEY = "tmdb_api_key"
    const val FLAT_FOLDERS = "detect_flat_folders"
    const val QUALITY = "preferred_quality"

    /**
     * Splits the configured extra folders. Entries are separated by newlines or semicolons so
     * folder names may contain commas.
     */
    fun parseExtraFolders(raw: String?): List<String> {
        val value = raw.orEmpty()
        if (value.isBlank()) return emptyList()

        return value.split('\n', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Comma separated form written by versions before newline support. */
    fun splitLegacyExtraFolders(raw: String?): List<String> = raw.orEmpty()
        .split(',', ';', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    /** Human readable "1h 59m" style duration used by the session status row. */
    fun formatDuration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private val REFRESH_TOKEN_JSON_REGEX = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"")
    private const val REFRESH_TOKEN_PARAM = "refresh_token="

    /**
     * Decodes a value read from page storage, which may be JSON, URL-encoded JSON, or plain text.
     */
    fun decodeWebValue(raw: String): String {
        if (!raw.contains('{') && !raw.contains("%7B", ignoreCase = true)) return raw
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    /**
     * Reads a refresh token out of the `credentials` value stored by PikPak's web app, which
     * can be the whole JSON object, a `refresh_token=...` fragment, or the bare token.
     */
    fun parseRefreshToken(decoded: String): String? {
        REFRESH_TOKEN_JSON_REGEX.find(decoded)?.groupValues?.get(1)?.let { return it }

        val keyIndex = decoded.lowercase(java.util.Locale.US).indexOf(REFRESH_TOKEN_PARAM)
        if (keyIndex >= 0) {
            return decoded.substring(keyIndex + REFRESH_TOKEN_PARAM.length)
                .substringBefore('&')
                .substringBefore(';')
                .trim('"', ' ', '\'')
                .takeIf { it.isNotBlank() }
        }

        return decoded.takeIf { it.length >= 20 && !it.contains(' ') && !it.contains('{') }
    }
}
