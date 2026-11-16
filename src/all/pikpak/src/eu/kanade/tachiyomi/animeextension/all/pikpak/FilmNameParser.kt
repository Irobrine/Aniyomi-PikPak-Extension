package eu.kanade.tachiyomi.animeextension.all.pikpak

import keiyoushi.utils.tryParse
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure file-name, season and date parsing for the PikPak drive layout.
 *
 * Everything here is free of Android and network dependencies so the heuristics that decide how a
 * drive listing becomes anime, seasons and episodes can be unit tested.
 */
internal object FilmNameParser {
    const val SEASON_SEPARATOR = "|"
    const val OTHER_SEASON = "Other"
    const val SYNTHETIC_SEASON_ID = "__episodes__"
    const val SYNTHETIC_SEASON_TITLE = "Episodes"

    fun parseSeasonUrl(url: String): Pair<String, String?> {
        val separator = url.indexOf(SEASON_SEPARATOR)
        if (separator == -1) return url to null

        val season = url.substring(separator + SEASON_SEPARATOR.length)
        val itemId = url.substring(0, separator)
        if (season.isBlank()) return itemId to null
        return itemId to season
    }

    /**
     * Derives the season label from the folder path below the anime folder, falling back to the
     * file name. `My Pack/Show/Season 2/E01.mkv` becomes `Season 2`.
     */
    fun pathSeasonKey(itemName: String, rawPath: String, fallbackName: String): String? = DirectoryAnalyzer.analyzePath(itemName, rawPath, fallbackName).seasonLabel

    /**
     * Season label per episode file, used when the folders do not describe seasons themselves.
     *
     * Only applies when files carry at least two distinct show-name prefixes, so a normal flat
     * folder is not split into one "season" per episode title.
     */
    fun assignFileNameSeasons(fileNames: List<String>, itemName: String): List<String?> {
        val itemClean = MediaText.normalizedKey(cleanAnimeName(itemName))
        val prefixes = fileNames
            .mapNotNull { fileNameSeasonPrefix(it) }
            .distinctBy { MediaText.normalizedKey(it) }
            .filterNot { MediaText.normalizedKey(it) == itemClean }

        if (prefixes.size < 2) return List(fileNames.size) { null }

        return fileNames.map { fileName ->
            val prefix = fileNameSeasonPrefix(fileName) ?: return@map null
            if (MediaText.normalizedKey(prefix) == itemClean) null else prefix
        }
    }

    fun fileNameSeasonPrefix(fileName: String): String? {
        val base = MediaText.baseName(fileName)
        val index = EPISODE_MARKERS
            .mapNotNull { regex -> regex.find(base)?.range?.first }
            .minOrNull()
            ?: return null
        if (index <= 0) return null

        val prefix = metadataSearchTitle(base.substring(0, index)).trim()
        return prefix.takeIf { it.isNotBlank() && !isVideoTypeTag(it) && !isGenericFolder(it) }
    }

    fun withOtherSeason(seasons: List<String?>): List<String?> {
        if (seasons.none { it != null }) return seasons
        return seasons.map { it ?: OTHER_SEASON }
    }

    fun seasonKeyFromFileName(fileName: String): String? = EpisodeParser.seasonKey(fileName)

    fun seasonSortKey(season: String): Int {
        if (isSpecialSeason(season)) return 0
        detectedSeasonNumber(season)?.let { return it }
        return Int.MAX_VALUE
    }

    fun detectedSeasonNumber(season: String): Int? {
        EpisodeParser.extractSeason(season)?.let { return it }
        EpisodeParser.partNumber(season)?.let { return it }
        EpisodeParser.courNumber(season)?.let { return it }

        BARE_SEASON_LABEL.find(season)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it !in 1900..2099) return it }
        return null
    }

    fun isSpecialSeason(season: String): Boolean {
        if (season.equals(OTHER_SEASON, ignoreCase = true)) return false
        if (EpisodeParser.specialLabel(season) != null) return true
        return detectedSeasonNumber(season) == 0
    }

    fun orderedSeasons(seasons: Iterable<String>): List<String> {
        val unique = seasons
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(::seasonIdentity)

        val special = unique
            .filter { isSpecialSeason(it) }
            .sortedWith(compareBy<String> { specialRank(it) }.thenComparator(::naturalCompare))

        val numbered = unique
            .filterNot { isSpecialSeason(it) }
            .filter { detectedSeasonNumber(it) != null }
            .sortedWith(
                compareBy<String> { detectedSeasonNumber(it) ?: Int.MAX_VALUE }
                    .thenComparator(::naturalCompare),
            )

        val named = unique
            .filterNot { isSpecialSeason(it) }
            .filter { detectedSeasonNumber(it) == null && !it.equals(OTHER_SEASON, ignoreCase = true) }
            .sortedWith(::naturalCompare)

        val ordered = special + numbered + named
        return if (unique.any { it.equals(OTHER_SEASON, ignoreCase = true) }) ordered + OTHER_SEASON else ordered
    }

    fun seasonDisplayTitle(parent: String, label: String): String {
        val base = parent.ifBlank { label }
        val clean = cleanAnimeName(label).ifBlank { label.trim() }
        if (clean.isBlank()) return base

        when (EpisodeParser.specialLabel(label)?.uppercase(Locale.ROOT)) {
            "SP" -> return appendLabel(base, if (label.any { it.isDigit() }) clean else "Specials")
            "OVA" -> return appendLabel(base, if (label.any { it.isDigit() }) clean else "OVA")
            "ONA" -> return appendLabel(base, if (label.any { it.isDigit() }) clean else "ONA")
            "MOVIE" -> return appendLabel(base, if (label.any { it.isDigit() }) clean else "Movie")
            "EXTRAS" -> return appendLabel(base, if (label.any { it.isDigit() }) clean else "Extras")
        }

        val number = detectedSeasonNumber(label)
        if (number == 0) return appendLabel(base, "Specials")

        if (number != null) {
            val part = EpisodeParser.partNumber(label)
            val cour = EpisodeParser.courNumber(label)
            val hasSeasonMarker = SEASON_LABEL_MARKER.containsMatchIn(label)
            val suffix = when {
                part != null && hasSeasonMarker -> " Part $part"
                cour != null && hasSeasonMarker -> " Cour $cour"
                part != null -> " Part $part"
                cour != null -> " Cour $cour"
                else -> ""
            }
            val qualifier = SEASON_QUALIFIER.find(label)?.groupValues?.get(1)?.trim()
            if (qualifier != null && qualifier.isNotBlank()) {
                val suffixText = String.format(Locale.US, "S%02d", number) + suffix
                val baseWithQualifier = if (MediaText.normalizedKey(base).endsWith(MediaText.normalizedKey(qualifier))) {
                    base
                } else {
                    appendLabel(base, qualifier)
                }
                return appendLabel(baseWithQualifier, suffixText)
            }
            return if (!hasSeasonMarker && (part != null || cour != null)) {
                appendLabel(base, clean)
            } else {
                appendLabel(base, String.format(Locale.US, "S%02d", number) + suffix)
            }
        }

        if (MediaText.normalizedKey(clean) == MediaText.normalizedKey(base)) return base
        return appendLabel(base, clean)
    }

    fun seasonNumberFromLabel(season: String): Double {
        val key = seasonSortKey(season)
        return if (key == Int.MAX_VALUE) -1.0 else key.toDouble()
    }

    /** Series name in a qualified season label such as `Show A - Season 1`. */
    fun seasonQualifier(season: String): String? = SEASON_QUALIFIER.find(season)
        ?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(season, ignoreCase = true) }

    /**
     * Semantic identity used to merge `S1`, `Season 1` and `1st Season` while keeping
     * `Season 1 Part 1` and `Season 1 Part 2` distinct.
     */
    fun seasonIdentity(season: String): String {
        val clean = season.trim()
        if (isSpecialSeason(clean)) {
            val kind = EpisodeParser.specialLabel(clean)?.uppercase(Locale.ROOT) ?: "SPECIAL"
            val number = EpisodeParser.specialNumber(clean) ?: detectedSeasonNumber(clean) ?: 0
            return "special:$kind:$number"
        }

        val number = detectedSeasonNumber(clean)
        if (number != null) {
            val qualifier = seasonQualifier(clean).orEmpty()
            val part = EpisodeParser.partNumber(clean) ?: 0
            val cour = EpisodeParser.courNumber(clean) ?: 0
            return "number:$number:${MediaText.normalizedKey(qualifier)}:$part:$cour"
        }
        return "name:${MediaText.normalizedKey(clean)}"
    }

    /** Maps every season label to the first label with the same semantic identity. */
    fun canonicalSeasonLabels(seasons: Iterable<String>): Map<String, String> {
        val labels = seasons.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val representative = mutableMapOf<String, String>()
        labels.forEach { label ->
            val identity = seasonIdentity(label)
            if (!representative.containsKey(identity)) representative[identity] = label
        }
        return labels.associateWith { representative[seasonIdentity(it)] ?: it }
    }

    fun parseEpisodeNumber(fileName: String): Int? = EpisodeParser.parseNumber(fileName)

    fun parseEpisode(fileName: String): ParsedEpisode? = EpisodeParser.parse(fileName)

    /**
     * Assigns contiguous episode numbers in natural file-name order.
     *
     * Detected numbers are kept; files without one continue from the previous number instead of
     * jumping past the highest number in the folder. Duplicate numbers are preserved because two
     * files can be alternate releases of the same episode; ordering breaks the tie by version and
     * name instead of silently dropping one.
     */
    fun numberEpisodes(filesInOrder: List<Pair<String, Int?>>): List<Int> = numberEpisodeValues(filesInOrder.map { (name, number) -> name to number?.toDouble() })
        .map { it.toInt() }

    fun numberEpisodeValues(filesInOrder: List<Pair<String, Double?>>): List<Double> {
        var next = 1.0
        return filesInOrder.map { (_, detected) ->
            val number = detected ?: next
            next = maxOf(next, Math.floor(number) + 1.0)
            number
        }
    }

    fun formatEpisode(episode: Int): String = String.format(Locale.US, "E%02d", episode)

    fun formatEpisode(
        episode: Double,
        endEpisode: Double? = null,
        kind: EpisodeKind = EpisodeKind.REGULAR,
    ): String = EpisodeParser.format(episode, endEpisode, kind)

    /** Parses PikPak's `created_time` (`2024-01-01T00:00:00.000+08:00`) into epoch millis. */
    fun parseDate(date: String?): Long {
        val raw = date?.trim().orEmpty()
        val match = ISO_DATE_REGEX.matchEntire(raw) ?: return 0L
        val time = match.groupValues[2].ifBlank { "00:00:00" }
        val base = synchronized(UTC_FORMATTER) {
            UTC_FORMATTER.tryParse(match.groupValues[1] + "T" + time)
        }
        if (base <= 0L) return 0L

        val offsetMs = parseUtcOffsetMs(match.groupValues[3])
        return (base - offsetMs).takeIf { it > 0L } ?: 0L
    }

    /**
     * Parses a media-link expiry. PikPak sends epoch seconds for links and ISO timestamps for
     * some responses, so both forms are accepted. Returns null when there is no expiry.
     */
    fun parseExpiryMillis(expiry: String?): Long? {
        val value = expiry?.trim().orEmpty()
        if (value.isEmpty()) return null

        value.toLongOrNull()?.let { epoch ->
            if (epoch <= 0L) return null
            return if (epoch > EPOCH_MILLIS_THRESHOLD) epoch else epoch * 1000
        }
        return parseDate(value).takeIf { it > 0L }
    }

    private fun parseUtcOffsetMs(zone: String): Long {
        if (zone.isBlank() || zone.equals("Z", ignoreCase = true)) return 0L

        val sign = if (zone.startsWith("-")) -1 else 1
        val digits = zone.drop(1).replace(":", "")
        val hours = digits.take(2).toIntOrNull() ?: return 0L
        val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0
        return sign * (hours * 60L + minutes) * 60_000L
    }

    /**
     * Title used for API lookups: drops release noise, episode markers and parenthesised years
     * that break AniList/TMDB matching, while the catalog keeps the original cleaned name.
     */
    fun metadataSearchTitle(name: String): String {
        var cleaned = cleanAnimeName(name)
        cleaned = EpisodeParser.stripEpisodeSuffix(cleaned, fromFileName = MediaText.isVideoFile(name))

        cleaned = PAREN_TAG_REGEX.replace(cleaned) { match ->
            if (isMetadataNoise(match.groupValues[1])) "" else match.value
        }
        cleaned = BRACKET_TAG_REGEX.replace(cleaned) { match ->
            if (isMetadataNoise(match.groupValues[1])) "" else match.value
        }
        cleaned = BARE_VIDEO_TAG_REGEX.replace(cleaned, " ")

        cleaned = WHITESPACE_REGEX.replace(cleaned, " ").trim(' ', '-', '_', '.', ',', ':', ';', '|')
        return cleaned.ifBlank { cleanAnimeName(name) }
    }

    /** Title shown for a single-file catalog entry, keeping years but dropping episode markers. */
    fun titleFromFileName(fileName: String): String {
        var title = cleanAnimeName(fileName)
        title = EpisodeParser.stripEpisodeSuffix(title, fromFileName = true)
        title = BARE_VIDEO_TAG_REGEX.replace(title, " ")
        return WHITESPACE_REGEX.replace(title, " ")
            .trim(' ', '-', '_', '.', ',', ':', ';', '|')
            .ifBlank { cleanAnimeName(fileName) }
    }

    /** Year mentioned in the folder name, used to reject releases that do not match. */
    fun metadataYear(name: String): Int? = MediaText.yearIn(name)

    fun isMetadataNoise(content: String): Boolean = MediaText.isNoiseTag(content)

    fun cleanAnimeName(name: String): String = MediaText.cleanTitle(name)

    fun isVideoTypeTag(tag: String): Boolean = MediaText.isVideoTypeTag(tag)

    fun isIdTag(tag: String): Boolean = MediaText.isIdTag(tag)

    fun isVideoFile(name: String): Boolean = MediaText.isVideoFile(name)

    /**
     * Compares names the way media players order episodes: digits are compared by value, so
     * `E2` sorts before `E10` instead of after it.
     */
    fun naturalCompare(left: String, right: String): Int {
        var i = 0
        var j = 0

        while (i < left.length && j < right.length) {
            val a = left[i]
            val b = right[j]

            if (a.isDigit() && b.isDigit()) {
                val aStart = i
                while (i < left.length && left[i].isDigit()) i++
                val bStart = j
                while (j < right.length && right[j].isDigit()) j++

                val aNumber = left.substring(aStart, i).trimStart('0')
                val bNumber = right.substring(bStart, j).trimStart('0')

                val byLength = aNumber.length.compareTo(bNumber.length)
                if (byLength != 0) return byLength

                val byValue = aNumber.compareTo(bNumber)
                if (byValue != 0) return byValue
            } else {
                val byChar = a.lowercaseChar().compareTo(b.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }

        return (left.length - i).compareTo(right.length - j)
    }

    fun sortInNaturalOrder(names: List<String>): List<String> = names.sortedWith(::naturalCompare)

    private fun appendLabel(base: String, label: String): String {
        if (label.isBlank()) return base
        if (base.isBlank()) return label
        if (MediaText.normalizedKey(base).endsWith(MediaText.normalizedKey(label))) return base
        return "$base $label"
    }

    private fun specialRank(season: String): Int = when (EpisodeParser.specialLabel(season)?.uppercase(Locale.ROOT)) {
        "SP" -> 0
        "MOVIE" -> 1
        "OVA" -> 2
        "ONA" -> 3
        "EXTRAS" -> 4
        else -> 5
    }

    private fun isGenericFolder(name: String): Boolean = GENERIC_FOLDER_REGEX.matches(cleanAnimeName(name))

    private val ISO_DATE_REGEX = Regex(
        "^(\\d{4}-\\d{2}-\\d{2})(?:[T ](\\d{2}:\\d{2}:\\d{2}))?(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$",
    )

    private const val EPOCH_MILLIS_THRESHOLD = 10_000_000_000L

    private val UTC_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val EPISODE_MARKERS = listOf(
        Regex("(?i)S(\\d{1,2})[\\s._-]*E(\\d{1,4})"),
        Regex("(?i)(?<![A-Za-z0-9])(\\d{1,2})\\s*[xX×]\\s*(\\d{1,4})"),
        Regex("(?i)(?<![A-Za-z0-9])(?:EP|Episode|Episodio|Épisode|Folge|Odcinek|Bölüm|Tập)\\s*[._#:-]?\\s*\\d{1,4}"),
        Regex("(?i)(?<![A-Za-z0-9])E(?:P)?[\\s._-]*\\d{1,4}"),
        Regex("(?<!\\p{L}\\p{N})[-–—]\\s*\\d{1,3}(?:v\\d+)?(?=\\s|$|[\\]()._])"),
        Regex("\\[(\\d{1,3})(?:v\\d+)?]"),
        Regex("(?i)\\[E(\\d{1,3})]"),
        Regex("\\((\\d{1,3})(?:v\\d+)?\\)"),
        Regex("(?i)\\(E(\\d{1,3})\\)"),
        Regex("(?i)(?<![0-9])(?:SP|OVA|ONA|OAD)(\\d{1,3})(?![0-9])"),
        Regex("第?\\d{1,4}[话話集回]"),
        Regex("\\d{1,4}\\s*(?:화|회)"),
        Regex("(?i)(?<![A-Za-z0-9])S\\d{1,2}(?![0-9])"),
        Regex("(?i)(?<![A-Za-z0-9])Season\\s*\\d{1,2}"),
        Regex("第\\s*\\d{1,3}\\s*[季期]"),
        Regex("(?<![\\p{L}\\p{N}])(?:제\\s*)?\\d{1,3}\\s*기(?![\\p{L}\\p{N}])"),
        Regex("(?i)(?<![A-Za-z0-9])(?:Part|Cour)\\s*\\d{1,2}"),
        Regex("(?i)(?:^|[\\s._-])(\\d{1,3})(?:v\\d+)?(?!\\.\\d)(?=[\\s._-]|$|\\[|\\()"),
    )
    private val BARE_SEASON_LABEL = Regex("(?i)^(?:season|saison|staffel|temporada|sezon|시즌|phần|cour|part)\\s*#?\\s*(\\d{1,3})$")
    private val SEASON_LABEL_MARKER = Regex(
        "(?i)(?:season|saison|staffel|temporada|sezon|seizoen|stagione|säsong|sesong|kausi|" +
            "сезон|시즌|phần|musim|[Ss]\\d{1,2}|第\\s*\\d+\\s*[季期])",
    )
    private val SEASON_QUALIFIER = Regex(
        "(?i)^(.+?)\\s*[-–—:：]\\s*(?=(?:s\\d{1,2}|season\\s*\\d{1,3}|\\d{1,2}(?:st|nd|rd|th)\\s+season|" +
            "part\\s*\\d{1,2}|cour\\s*\\d{1,2}|第\\s*\\d+\\s*[季期]))",
    )
    private val GENERIC_FOLDER_REGEX = Regex(
        "(?i)(?:video|videos|episode|episodes|ep|eps|complete|batch|download|downloads|tv|" +
            "anime|sub|subs|subtitle|subtitles|multi|raw|raws|encoded|output|new folder|untitled|" +
            "my pack|my shared|shared|drive|media|library|extras?)",
    )

    private val PAREN_TAG_REGEX = Regex("\\(([^)]*)\\)")
    private val BRACKET_TAG_REGEX = Regex("\\[([^\\]]+)]")
    private val BARE_VIDEO_TAG_REGEX = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:" +
            "\\d{3,4}p|4k|8k|uhd|web[- ]?dl|web[- ]?rip|blu[- ]?ray|bd[- ]?rip|br[- ]?rip|" +
            "hd[- ]?rip|dvd[- ]?rip|hdtv|tv[- ]?rip|remux|h\\.?26[45]|x\\.?26[45]|hevc|avc\\d?|" +
            "av1|vp9|hdr10(?:\\+|plus)?|hdr|dolby[- ]?vision|aac(?:\\d\\.\\d)?|ac3(?:\\d\\.\\d)?|" +
            "eac3(?:\\d\\.\\d)?|dts(?:[- ]?hd)?(?:\\d\\.\\d)?|truehd|atmos|flac|opus|" +
            "(?:8|10|12)[- ]?bit|multi[- ]?audio|dual[- ]?audio|softsub|hardsub|subbed|dubbed|" +
            "proper|repack|extended" +
            ")(?![\\p{L}\\p{N}])",
    )
    private val WHITESPACE_REGEX = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
}
