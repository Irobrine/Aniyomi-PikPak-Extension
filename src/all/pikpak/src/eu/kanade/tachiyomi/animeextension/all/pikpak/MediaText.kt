package eu.kanade.tachiyomi.animeextension.all.pikpak

import java.text.Normalizer
import java.util.Locale

/**
 * Unicode-aware text helpers shared by the catalog, episode parser and metadata matcher.
 *
 * Drive names come from every platform PikPak supports. They can contain full-width digits,
 * combining marks, bidi controls, release-group brackets and a mixture of separators. All of that
 * is normalised here once so the heuristics elsewhere can reason about plain, comparable text.
 */
internal object MediaText {
    /** Common video containers, including the ones PikPak stores from torrents and discs. */
    val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".avi", ".flv", ".mov", ".webm", ".wmv", ".m4v",
        ".ts", ".m2ts", ".mts", ".mpg", ".mpeg", ".3gp", ".ogv", ".ogm",
        ".rm", ".rmvb", ".asf", ".vob", ".divx", ".xvid", ".iso",
    )

    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")
    private val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val VIDEO_EXTENSION = Regex(
        "(?i)\\.(?:" +
            "mp4|mkv|avi|flv|mov|webm|wmv|m4v|ts|m2ts|mts|mpg|mpeg|3gp|ogv|ogm|" +
            "rm|rmvb|asf|vob|divx|xvid|iso" +
            ")$",
    )
    private val BRACKET_TAG = Regex("\\[([^\\]]+)]")
    private val LEADING_BRACKET = Regex("^\\s*\\[[^\\]]+]\\s*")
    private val TRAILING_BRACKET = Regex("\\s*\\[([^\\]]+)]\\s*$")
    private val TRAILING_SEPARATORS = charArrayOf(' ', '-', '_', '.', ',', ':', ';', '|', '/', '\\')
    private val DIACRITICS = Regex("\\p{M}+")

    /** NFKC folds full-width characters and compatibility forms into their plain equivalents. */
    fun normalizeUnicode(value: String): String {
        if (value.isEmpty()) return value
        val normalized = runCatching { Normalizer.normalize(value, Normalizer.Form.NFKC) }
            .getOrDefault(value)
        return CONTROL_CHARS.replace(normalized, " ")
    }

    /** File name without its video extension, still containing every other tag. */
    fun baseName(fileName: String): String = VIDEO_EXTENSION.replace(normalizeUnicode(fileName).trim(), "")

    fun hasVideoExtension(fileName: String): Boolean = VIDEO_EXTENSIONS.any { fileName.endsWith(it, ignoreCase = true) }

    fun isVideoFile(fileName: String): Boolean = hasVideoExtension(normalizeUnicode(fileName).trim())

    fun collapseWhitespace(value: String): String = WHITESPACE.replace(value, " ").trim()

    fun trimSeparators(value: String): String = value.trim(*TRAILING_SEPARATORS)

    /**
     * Name shown in the catalog. Release-group brackets at the start, quality/ID tags and the
     * extension are removed, while episode information is deliberately kept: a single-file entry
     * such as `[Group] Show - 01 [1080p].mkv` should still show its episode marker.
     */
    fun cleanTitle(name: String): String {
        if (name.isBlank()) return name

        var cleaned = normalizeUnicode(name).trim()

        // A leading `[Group]` (or several) is release metadata, not part of the title.
        while (true) {
            val match = LEADING_BRACKET.find(cleaned) ?: break
            val rest = cleaned.substring(match.range.last + 1).trimStart(*TRAILING_SEPARATORS)
            if (rest.isBlank()) break
            cleaned = rest
        }

        cleaned = BRACKET_TAG.replace(cleaned) { match ->
            if (isNoiseTag(match.groupValues[1])) "" else match.value
        }
        while (true) {
            val match = TRAILING_BRACKET.find(cleaned) ?: break
            val content = match.groupValues[1]
            if (!isReleaseGroupTag(content)) break
            val before = cleaned.substring(0, match.range.first).trim()
            if (before.isBlank()) break
            cleaned = before
        }
        cleaned = BARE_RELEASE_TAG.replace(cleaned, " ")
        cleaned = cleaned.replace('_', ' ')
        cleaned = VIDEO_EXTENSION.replace(cleaned, "")
        cleaned = collapseWhitespace(cleaned)
        return trimSeparators(cleaned).ifBlank { trimSeparators(collapseWhitespace(normalizeUnicode(name))) }
    }

    /**
     * Lowercase, accent-folded key used for matching. Punctuation is dropped but letters from
     * every script are kept, so Japanese, Korean, Cyrillic and Latin titles all remain comparable.
     */
    fun normalizedKey(value: String): String {
        val folded = foldDiacritics(normalizeUnicode(value).lowercase(Locale.ROOT))
        val out = StringBuilder(folded.length)
        var previousSpace = true
        folded.forEach { character ->
            if (character.isLetterOrDigit()) {
                out.append(character)
                previousSpace = false
            } else if (!previousSpace) {
                out.append(' ')
                previousSpace = true
            }
        }
        return out.toString().trim()
    }

    /** Punctuation-free key, used by the legacy Dice similarity. */
    fun compactKey(value: String): String = normalizedKey(value).replace(" ", "")

    fun tokens(value: String): List<String> = normalizedKey(value)
        .split(' ')
        .filter { it.isNotBlank() }

    fun foldDiacritics(value: String): String {
        if (value.isEmpty()) return value
        val decomposed = runCatching { Normalizer.normalize(value, Normalizer.Form.NFD) }.getOrDefault(value)
        return DIACRITICS.replace(decomposed, "")
            .replace("ß", "ss")
            .replace("æ", "ae")
            .replace("Æ", "AE")
            .replace("œ", "oe")
            .replace("Œ", "OE")
            .replace("ø", "o")
            .replace("Ø", "O")
            .replace("ł", "l")
            .replace("Ł", "L")
            .replace("đ", "d")
            .replace("Đ", "D")
            .replace("ð", "d")
            .replace("Ð", "D")
            .replace("þ", "th")
            .replace("Þ", "Th")
            .replace("ı", "i")
            .replace("İ", "I")
    }

    /** Bracket/parenthesis content that should never be part of a title. */
    fun isNoiseTag(content: String): Boolean {
        val value = collapseWhitespace(normalizeUnicode(content))
        if (value.isBlank()) return true
        if (YEAR_OR_RANGE.matches(value)) return true
        value.toIntOrNull()?.let { if (isResolution(it)) return true }
        if (value.lowercase(Locale.ROOT) in METADATA_NOISE_WORDS) return true
        if (isVideoTypeTag(value)) return true
        if (isIdTag(value)) return true
        // A bracket containing only a language or a release group abbreviation is noise too.
        if (LANGUAGE_TAG.matches(value)) return true
        return false
    }

    fun isVideoTypeTag(tag: String): Boolean = VIDEO_TYPE.containsMatchIn(normalizeUnicode(tag))

    fun isIdTag(tag: String): Boolean = ID_TAG.matches(normalizeUnicode(tag).trim())

    /**
     * A trailing bracket such as `[SubsPlease]` or `[ReinForce]` is a release group, while
     * `[Oshi no Ko]` or `[Part 2]` are title/season fragments and must stay.
     */
    fun isReleaseGroupTag(content: String): Boolean {
        val value = collapseWhitespace(normalizeUnicode(content))
        if (value.isBlank() || value.any { it.isWhitespace() }) return false
        if (value.length !in 2..24) return false
        if (isNoiseTag(value)) return false
        if (value.all { it.isDigit() }) return false
        return value.any { it.isUpperCase() } || value.any { it.isDigit() }
    }

    /** Returns the first plausible release year, preferring a parenthesised/bracketed one. */
    fun yearIn(name: String): Int? {
        val normalized = normalizeUnicode(name)
        PAREN_YEAR.find(normalized)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        BRACKET_YEAR.find(normalized)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return YEAR.findAll(normalized)
            .firstNotNullOfOrNull { match ->
                val value = match.groupValues[1].toIntOrNull() ?: return@firstNotNullOfOrNull null
                val before = normalized.substring(0, match.range.first)
                val after = normalized.substring(match.range.last + 1)
                // `2001: A Space Odyssey` starts with a title year, not a release year.
                if (before.isBlank() && after.isNotBlank()) return@firstNotNullOfOrNull null
                if (after.trimStart().startsWith(":")) return@firstNotNullOfOrNull null
                value
            }
    }

    fun isResolution(value: Int): Boolean = value in RESOLUTIONS

    private val RESOLUTIONS = setOf(144, 240, 288, 360, 432, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

    private val YEAR = Regex("(?<!\\d)((?:19|20)\\d{2})(?!\\d)")
    private val PAREN_YEAR = Regex("\\(((?:19|20)\\d{2})\\)")
    private val BRACKET_YEAR = Regex("\\[((?:19|20)\\d{2})]")
    private val YEAR_OR_RANGE = Regex(
        "(?i)^(?:19|20)\\d{2}(?:\\s*[-~–—]\\s*(?:(?:19|20)\\d{2}|present|ongoing))?$",
    )
    private val LANGUAGE_TAG = Regex(
        "(?i)^(?:eng|english|jpn|japanese|chi|chinese|kor|korean|spa|spanish|fre|french|ger|german|" +
            "ita|italian|por|portuguese|rus|russian|ara|arabic|hin|hindi|tha|thai|vie|vietnamese|" +
            "multi|dual|sub|subs|dub|dubbed|subbed|raw|raws|jap|jp|chs|cht|gb|big5)$",
    )

    private val ID_TAG = Regex("(?i)(?=.*\\d)[0-9a-f]{6,16}")
    private val BARE_RELEASE_TAG = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:" +
            "\\d{3,4}[pi]|4k|8k|uhd|" +
            "web[- ]?dl|web[- ]?rip|blu[- ]?ray|bd[- ]?rip|br[- ]?rip|hd[- ]?rip|" +
            "dvd[- ]?rip|hdtv|tv[- ]?rip|remux|" +
            "h\\.?26[45]|x\\.?26[45]|hevc|avc\\d?|av1|vp9|xvid|divx|" +
            "hdr10(?:\\+|plus)?|hdr|dolby[- ]?vision|" +
            "aac(?:\\d\\.\\d)?|ac3(?:\\d\\.\\d)?|eac3(?:\\d\\.\\d)?|dts(?:[- ]?hd)?(?:\\d\\.\\d)?|" +
            "truehd|atmos|flac|opus|mp3|ddp?(?:\\d\\.\\d)?|" +
            "(?:8|10|12)[- ]?bit|dual[- ]?audio|multi[- ]?audio|multi[- ]?subs?|" +
            "softsub|hardsub|subbed|dubbed|proper|repack|extended" +
            ")(?![\\p{L}\\p{N}])",
    )
    private val VIDEO_TYPE = Regex(
        "(?i)(?:\\b|_)(?:" +
            "\\d{3,4}[pi]|4k|8k|uhd|" +
            "web[- ]?dl|web[- ]?rip|blu[- ]?ray|bd[- ]?rip|br[- ]?rip|hd[- ]?rip|dvd[- ]?rip|hdtv|tv[- ]?rip|remux|" +
            "h\\.?26[45]|x\\.?26[45]|hevc|avc\\d?|av1|vp9|xvid|divx|" +
            "hdr10(?:\\+|plus)?|hdr|dolby[- ]?vision|dv|" +
            "aac(?:\\d\\.\\d)?|ac3(?:\\d\\.\\d)?|eac3(?:\\d\\.\\d)?|dts(?:[- ]?hd)?(?:\\d\\.\\d)?|truehd|atmos|flac|opus|mp3|ddp?(?:\\d\\.\\d)?|" +
            "(?:8|10|12)[- ]?bit|" +
            "dual[- ]?audio|multi[- ]?audio|multi[- ]?subs?|softsub|hardsub|" +
            "eng(?:lish)?[- ]?sub|baha|cr|nf|amzn|dsnp|hulu" +
            ")(?:\\b|_)",
    )

    private val METADATA_NOISE_WORDS = setOf(
        "tv", "tv series", "series", "movie", "film", "ova", "ona", "oad", "special", "specials",
        "complete", "batch", "uncensored", "uncut", "remaster", "remastered",
        "bd", "dvd", "web", "webrip", "web-dl", "webdl", "bluray", "blu-ray",
        "multi", "multisub", "multi sub", "dual audio",
        "sub", "subs", "dub", "dubbed", "subbed", "english sub", "eng sub",
        "1080p", "720p", "480p", "2160p", "4k", "8k", "hevc", "avc", "x264", "x265", "h264", "h265",
        "10bit", "8bit", "hdr", "remux", "proper", "repack", "extended", "directors cut",
        "amzn", "nf", "cr", "baha", "dsnp", "hulu",
    )
}
