package eu.kanade.tachiyomi.animeextension.all.pikpak

/** Preference keys and small pure helpers shared by the source and its settings screen. */
internal object PikPakPrefs {
    const val SESSION_SEED = "pikpak_refresh_token_input"
    const val DEVICE_ID = "pikpak_device_id"
    const val EXTRA_FOLDERS = "pikpak_extra_folders"
    const val EXTRA_FOLDERS_LINES = "pikpak_extra_folders_lines"
    const val CLOUD_DOWNLOADS = "pikpak_cloud_downloads"
    const val CLOUD_DESTINATION = "pikpak_cloud_destination"

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

    // Ids and answers remembered between app processes, so the catalog does not have to walk the
    // drive from its root or probe every folder again on a cold start.
    const val MY_PACK_ID = "cached_my_pack_id"
    const val EXTRA_FOLDER_IDS = "cached_extra_folder_ids"
    const val EXTRA_FOLDER_NAMES = "cached_extra_folder_names"
    const val FOLDER_LAYOUTS = "cached_folder_layouts"
    const val CAPTCHA_TOKEN = "cached_captcha_token"
    const val CAPTCHA_ACTION = "cached_captcha_action"
    const val CAPTCHA_EXPIRES_AT = "cached_captcha_expires_at"

    /**
     * Pairs each link line with the name typed on the same line.
     *
     * Blank link lines are separators and are skipped, blank name lines keep that download's
     * original name, and missing name lines default the same way.
     */
    fun parseCloudEntries(links: String?, names: String?): List<Pair<String, String?>> {
        val nameLines = names.orEmpty().lines()
        return links.orEmpty().lines().mapIndexedNotNull { index, line ->
            val link = line.trim()
            if (link.isEmpty()) return@mapIndexedNotNull null
            link to nameLines.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

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

    /** Splits the cloud download field: one link per line, blanks and repeats dropped. */
    fun parseCloudLinks(raw: String?): List<String> = raw.orEmpty()
        .split('\n', '\r')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    /**
     * Readable label for a submitted link, used until PikPak reports the real file name.
     * Magnet links expose their display name in `dn`; other links are shortened.
     */
    fun cloudLinkLabel(url: String): String? {
        if (url.startsWith("magnet:", ignoreCase = true)) {
            val name = MAGNET_NAME_REGEX.find(url)?.groupValues?.get(1) ?: return null
            return runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
                .takeIf { it.isNotBlank() }
        }

        return url.substringAfter("://", url).trim().take(MAX_LINK_LABEL).takeIf { it.isNotBlank() }
    }

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
    private const val MAX_LINK_LABEL = 80
    private val MAGNET_NAME_REGEX = Regex("[?&]dn=([^&]+)")

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
