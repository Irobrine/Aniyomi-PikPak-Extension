package eu.kanade.tachiyomi.animeextension.all.pikpak

import java.util.Locale

internal enum class EpisodeKind {
    REGULAR,
    SPECIAL,
    DECIMAL,
}

/**
 * One episode marker found in a file name.
 *
 * [value] keeps decimals (5.5) and the first episode of a range (1 for `E01-E03`). [endValue] is
 * only set for ranges. [season] is only set when the file name itself carries a real season number
 * (`S02E03`, `2x03`, `Season 2 - 03`); folder-level seasons are attached later.
 */
internal data class ParsedEpisode(
    val value: Double,
    val endValue: Double? = null,
    val season: Int? = null,
    val kind: EpisodeKind = EpisodeKind.REGULAR,
    val version: Int = 0,
    val confidence: Int = 0,
    val source: String = "",
) {
    val number: Int get() = value.toInt()
    val isRange: Boolean get() = endValue != null && endValue > value
    val isDecimal: Boolean get() = value != value.toInt().toDouble()
}

/**
 * Recognises episode and season markers in multilingual release names.
 *
 * Real libraries mix AniList/TMDB-friendly names, scene releases, fansub names and renamed files.
 * The parser therefore collects every plausible marker, scores explicit markers above bare
 * numbers, and rejects numbers that are demonstrably something else (years, resolutions, codecs,
 * bit depths, audio channels, discs, parts and seasons).
 */
internal object EpisodeParser {
    private val CASE_INSENSITIVE = setOf(RegexOption.IGNORE_CASE)

    private val SEASON_EPISODE = Regex(
        "(?<![\\p{L}\\p{N}])[Ss](\\d{1,2})(?:" +
            "[\\s._:-]*(?:[Ee][Pp]?|Episode|Episodio|Episódio|Épisode|Folge|Aflevering|Odcinek|" +
            "Epizod|Bölüm|Bolum|Cap[ií]tulo|Tập|Tap|Сери[яи]|Эпизод|Епізод|에피소드|エピソード)" +
            "[\\s._:-]*|[\\s._:-]+)" +
            "(\\d{1,4})(?:\\.(\\d{1,2}))?(?:[\\s._-]*[vV](\\d{1,2}))?" +
            "(?:\\s*(?:-|~|–|—|\\+|to)\\s*(?:[Ee][Pp]?)?[\\s._-]*(\\d{1,4}))?" +
            "(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val CROSS_SEASON_EPISODE = Regex(
        "(?<![\\p{L}\\p{N}])(\\d{1,2})\\s*[xX×]\\s*(\\d{1,4})(?:\\.(\\d{1,2}))?(?:[\\s._-]*[vV](\\d{1,2}))?" +
            "(?:\\s*(?:-|~|–|—|\\+)\\s*(\\d{1,4}))?(?![\\p{L}\\p{N}])",
    )

    /** `S01E01E02` and `E01E02` pack two episodes into one file without a range separator. */
    private val MULTI_EPISODE = Regex(
        "(?<![\\p{L}\\p{N}])(?:[Ss](\\d{1,2})[\\s._-]*)?[Ee](?:[Pp])?[\\s._-]*(\\d{1,4})[\\s._-]*[Ee](?:[Pp])?[\\s._-]*(\\d{1,4})" +
            "(?![\\p{L}\\p{N}])",
    )

    private val WORD_EPISODE = Regex(
        "(?<![\\p{L}\\p{N}])(?:EP|ÉP|EPISODE|EPISÓDIO|EPISODIO|ÉPISODE|FOLGE|AFLEVERING|ODC|" +
            "ODCINEK|ODCINKI|EPIZOD|EPIZODA|BÖL|BÖLÜM|BOLUM|CAP|CAPÍTULO|CAPITULO|CAPITOL|" +
            "TẬP|TAP|ตอนที่|ตอน|СЕРИЯ|СЕРІЯ|ЭПИЗОД|ЕПІЗОД|에피소드|エピソード|화수|회차)" +
            "[\\s._#:*-]*(?:[Ee][Pp]?)?[\\s._#:*-]*(\\d{1,4})(?:\\.(\\d{1,2}))?(?:[\\s._-]*[vV](\\d{1,2}))?" +
            "(?:\\s*(?:-|~|–|—|\\+)\\s*(?:[Ee][Pp]?)?[\\s._#:*-]*(\\d{1,4}))?" +
            "(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val SINGLE_E = Regex(
        "(?<![\\p{L}\\p{N}])(?:[Ee][Pp]?)[\\s._-]*(\\d{1,4})(?:\\.(\\d{1,2}))?(?:[\\s._-]*[vV](\\d{1,2}))?" +
            "(?:\\s*(?:-|~|–|—|\\+)\\s*(?:[Ee][Pp]?)?[\\s._-]*(\\d{1,4}))?" +
            "(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val SPECIAL_EPISODE = Regex(
        "(?<![\\p{L}\\p{N}])(SP|SPECIALS?|OVA|OAV|OAD|ONA|EXTRAS?|BONUS|NCOP|NCED|NC|RECAP|PILOT|" +
            "PROLOGUE|特典|映像特典|劇場版|剧场版)[\\s._#:*-]*(\\d{1,3})(?:[\\s._-]*[vV](\\d{1,2}))?" +
            "(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val BRACKET_EXPLICIT = Regex(
        "\\[\\s*(?:[Ee][Pp]?|Episode|第)?\\s*(\\d{1,4})(?:\\.(\\d{1,2}))?(?:[\\s._-]*[vV](\\d{1,2}))?\\s*" +
            "(?:[话話集回])?\\s*]",
        CASE_INSENSITIVE,
    )

    private val PAREN_EXPLICIT = Regex(
        "\\(\\s*(?:[Ee][Pp]?|Episode|第)?\\s*(\\d{1,4})(?:\\.(\\d{1,2}))?(?:[\\s._-]*[vV](\\d{1,2}))?\\s*" +
            "(?:[话話集回])?\\s*\\)",
        CASE_INSENSITIVE,
    )

    private val CHINESE_EPISODE = Regex(
        "(?:第\\s*(\\d{1,4})\\s*[话話集回]|(?<![\\p{L}\\p{N}])(\\d{1,4})\\s*[话話集回](?![\\p{L}\\p{N}]))",
    )

    private val KOREAN_EPISODE = Regex(
        "(?<![\\p{L}\\p{N}])(?:제\\s*)?(\\d{1,4})\\s*(?:화|회)(?![\\p{L}\\p{N}])",
    )

    private val GENERIC_RANGE = Regex(
        "(?<![\\p{L}\\p{N}])(\\d{1,3})(?:\\.(\\d{1,2}))?\\s*(?:-|~|–|—|\\+|to)\\s*" +
            "(\\d{1,3})(?:\\.(\\d{1,2}))?(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val NUMERIC_TOKEN = Regex(
        "(?<![\\p{L}\\p{N}])(\\d{1,4})(?:\\.(\\d{1,2}))?(?:[\\s._-]*[vV](\\d{1,2}))?(?![\\p{L}\\p{N}])",
    )

    private val SEASON_WORD = Regex(
        "(?<![\\p{L}\\p{N}])(?:season|saison|staffel|temporada|sezon|seizoen|stagione|säsong|" +
            "sesong|kausi|сезон|시즌|phần|musim)\\s*#?\\s*(\\d{1,3})(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val SEASON_BARE = Regex(
        "(?<![\\p{L}\\p{N}])[Ss](\\d{1,2})(?=$|[\\s._\\-\\)\\]]|[Ee](?:\\d|[Pp])|\\()",
    )

    private val SEASON_ORDINAL = Regex(
        "(?<![\\p{L}\\p{N}])(\\d{1,2})(?:st|nd|rd|th)\\s+season(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val SEASON_ROMAN = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:season|saison|staffel|temporada|sezon)\\s*#?\\s*(ii|iii|iv)(?![\\p{L}\\p{N}])",
    )

    private val ROMAN_SEASON_NUMBERS = mapOf("ii" to 2, "iii" to 3, "iv" to 4)

    private val SEASON_CJK = Regex("第\\s*(\\d{1,3})\\s*[季期]")
    private val SEASON_KOREAN = Regex("(?<![\\p{L}\\p{N}])(?:제\\s*)?(\\d{1,3})\\s*기(?![\\p{L}\\p{N}])")

    private val PART_WORD = Regex(
        "(?<![\\p{L}\\p{N}])(?:part|partie|parte|teil|часть|phần|parça|parte|قسمت)\\s*#?\\s*" +
            "(\\d{1,2})(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val COUR_WORD = Regex(
        "(?<![\\p{L}\\p{N}])(?:cour|クール|코트)\\s*#?\\s*(\\d{1,2})(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE,
    )

    private val SPECIAL_LABEL = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(SP|SPECIALS?|OVA|OAV|OAD|ONA|EXTRAS?|BONUS|NCOP|NCED|NC|RECAP|" +
            "PILOT|PROLOGUE|MOVIE|FILM|GEKIJOUBAN|劇場版|剧场版|特典|映像特典|映画)" +
            "(?:[\\s._#:*-]*\\d{1,3})?[\\s._-]*$",
    )

    private val DATE_PATTERN = Regex(
        "(?<!\\d)\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}(?!\\d)",
    )

    private val SPACED_RANGE = Regex("^\\d{1,3}\\s+[-–—]\\s+\\d{1,3}$")

    private val RESOLUTIONS = setOf(144, 240, 288, 360, 432, 480, 540, 576, 720, 1080, 1440, 2160, 4320)

    private val PART_CONTEXT = setOf(
        "part", "partie", "parte", "teil", "cour", "クール", "season", "saison", "staffel",
        "temporada", "sezon", "seizoen", "stagione", "disc", "disk", "dvd", "cd", "volume", "vol",
        "часть", "phần", "parça", "chapter", "chap", "episode", "ep", "act", "book", "arc",
    )

    private val AUDIO_CONTEXT = setOf(
        "aac", "ac3", "eac3", "ddp", "dd", "dts", "truehd", "flac", "opus", "mp3", "atmos",
    )

    fun parse(fileName: String): ParsedEpisode? {
        val base = MediaText.baseName(fileName)
        if (base.isBlank()) return null

        val candidates = mutableListOf<Candidate>()

        fun add(
            value: Int?,
            fraction: Int?,
            endValue: Int?,
            season: Int?,
            kind: EpisodeKind,
            version: Int?,
            score: Int,
            start: Int,
            source: String,
        ) {
            if (value == null || value < 0 || value > 9999) return
            if (endValue != null && (endValue < value || endValue - value > 99)) return
            if (value == 0 && kind == EpisodeKind.REGULAR && !source.startsWith("explicit")) return

            val numeric = value.toDouble() + fractionToDecimal(fraction)
            val endNumeric = endValue?.toDouble()?.plus(fractionToDecimal(fraction))
            if (!plausible(value, fraction, source.startsWith("explicit"))) return

            val effectiveKind = when {
                kind == EpisodeKind.SPECIAL || season == 0 || value == 0 -> EpisodeKind.SPECIAL
                numeric != numeric.toInt().toDouble() -> EpisodeKind.DECIMAL
                else -> EpisodeKind.REGULAR
            }
            candidates += Candidate(
                value = numeric,
                endValue = endNumeric,
                season = season,
                kind = effectiveKind,
                version = version ?: 0,
                score = score,
                start = start,
                source = source,
            )
        }

        SEASON_EPISODE.findAll(base).forEach { match ->
            val season = match.groupValues[1].toIntOrNull()
            val value = match.groupValues[2].toIntOrNull()
            add(
                value = value,
                fraction = match.groupValues[3].toIntOrNull(),
                endValue = match.groupValues[5].toIntOrNull(),
                season = season,
                kind = EpisodeKind.REGULAR,
                version = match.groupValues[4].toIntOrNull(),
                score = 130,
                start = match.range.first,
                source = "explicitSeasonEpisode",
            )
        }

        CROSS_SEASON_EPISODE.findAll(base).forEach { match ->
            val season = match.groupValues[1].toIntOrNull()
            val value = match.groupValues[2].toIntOrNull()
            if (season != null && season in 0..99) {
                add(
                    value = value,
                    fraction = match.groupValues[3].toIntOrNull(),
                    endValue = match.groupValues[5].toIntOrNull(),
                    season = season,
                    kind = EpisodeKind.REGULAR,
                    version = match.groupValues[4].toIntOrNull(),
                    score = 124,
                    start = match.range.first,
                    source = "explicitCrossSeason",
                )
            }
        }

        MULTI_EPISODE.findAll(base).forEach { match ->
            add(
                value = match.groupValues[2].toIntOrNull(),
                fraction = null,
                endValue = match.groupValues[3].toIntOrNull(),
                season = match.groupValues[1].toIntOrNull(),
                kind = EpisodeKind.REGULAR,
                version = null,
                score = 122,
                start = match.range.first,
                source = "explicitMultiEpisode",
            )
        }

        WORD_EPISODE.findAll(base).forEach { match ->
            add(
                value = match.groupValues[1].toIntOrNull(),
                fraction = match.groupValues[2].toIntOrNull(),
                endValue = match.groupValues[4].toIntOrNull(),
                season = null,
                kind = EpisodeKind.REGULAR,
                version = match.groupValues[3].toIntOrNull(),
                score = 118,
                start = match.range.first,
                source = "explicitWord",
            )
        }

        SINGLE_E.findAll(base).forEach { match ->
            add(
                value = match.groupValues[1].toIntOrNull(),
                fraction = match.groupValues[2].toIntOrNull(),
                endValue = match.groupValues[4].toIntOrNull(),
                season = null,
                kind = EpisodeKind.REGULAR,
                version = match.groupValues[3].toIntOrNull(),
                score = 112,
                start = match.range.first,
                source = "explicitE",
            )
        }

        SPECIAL_EPISODE.findAll(base).forEach { match ->
            add(
                value = match.groupValues[2].toIntOrNull(),
                fraction = null,
                endValue = null,
                season = null,
                kind = EpisodeKind.SPECIAL,
                version = match.groupValues[3].toIntOrNull(),
                score = 108,
                start = match.range.first,
                source = "explicitSpecial",
            )
        }

        BRACKET_EXPLICIT.findAll(base).forEach { match ->
            val explicit = match.value.any { it == 'E' || it == 'e' || it == '第' || it == '话' || it == '話' || it == '集' || it == '回' }
            add(
                value = match.groupValues[1].toIntOrNull(),
                fraction = match.groupValues[2].toIntOrNull(),
                endValue = null,
                season = null,
                kind = EpisodeKind.REGULAR,
                version = match.groupValues[3].toIntOrNull(),
                score = if (explicit) 104 else 72,
                start = match.range.first,
                source = if (explicit) "explicitBracket" else "bracket",
            )
        }

        PAREN_EXPLICIT.findAll(base).forEach { match ->
            val explicit = match.value.any { it == 'E' || it == 'e' || it == '第' || it == '话' || it == '話' || it == '集' || it == '回' }
            add(
                value = match.groupValues[1].toIntOrNull(),
                fraction = match.groupValues[2].toIntOrNull(),
                endValue = null,
                season = null,
                kind = EpisodeKind.REGULAR,
                version = match.groupValues[3].toIntOrNull(),
                score = if (explicit) 100 else 68,
                start = match.range.first,
                source = if (explicit) "explicitParen" else "paren",
            )
        }

        CHINESE_EPISODE.findAll(base).forEach { match ->
            val value = match.groupValues[1].ifBlank { match.groupValues[2] }.toIntOrNull()
            add(
                value = value,
                fraction = null,
                endValue = null,
                season = null,
                kind = EpisodeKind.REGULAR,
                version = null,
                score = 116,
                start = match.range.first,
                source = "explicitChinese",
            )
        }

        KOREAN_EPISODE.findAll(base).forEach { match ->
            add(
                value = match.groupValues[1].toIntOrNull(),
                fraction = null,
                endValue = null,
                season = null,
                kind = EpisodeKind.REGULAR,
                version = null,
                score = 114,
                start = match.range.first,
                source = "explicitKorean",
            )
        }

        // Ranges are checked before single numbers so `01-03` becomes one two-episode entry.
        GENERIC_RANGE.findAll(base).forEach { match ->
            if (looksLikeDate(base, match.range.first, match.range.last + 1)) return@forEach
            // `Show 2 - 05` is far more likely "title ends in 2, episode 05" than episodes 2-5.
            if (SPACED_RANGE.matches(match.value) && !match.groupValues[1].startsWith("0")) return@forEach
            val startValue = match.groupValues[1].toIntOrNull()
            val endValue = match.groupValues[3].toIntOrNull()
            if (startValue == null || endValue == null) return@forEach
            add(
                value = startValue,
                fraction = match.groupValues[2].toIntOrNull(),
                endValue = endValue,
                season = null,
                kind = EpisodeKind.REGULAR,
                version = null,
                score = 66,
                start = match.range.first,
                source = "range",
            )
        }

        NUMERIC_TOKEN.findAll(base).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            if (looksLikeDate(base, start, end)) return@forEach
            // A decimal tail that the token regex had to backtrack over (5.1ch, 7.1 audio)
            // must not become a bare episode number.
            if ((base.getOrNull(end) == '.' || base.getOrNull(end) == ',') &&
                base.getOrNull(end + 1)?.isDigit() == true
            ) {
                return@forEach
            }
            // The fractional half of a decimal must not be read as its own episode number.
            if ((base.getOrNull(start - 1) == '.' || base.getOrNull(start - 1) == ',') &&
                base.getOrNull(start - 2)?.isDigit() == true
            ) {
                return@forEach
            }
            val value = match.groupValues[1].toIntOrNull() ?: return@forEach
            val fraction = match.groupValues[2].toIntOrNull()
            val version = match.groupValues[3].toIntOrNull()

            val before = base.substring(0, start).trimEnd(*SEPARATORS)
            val after = base.substring(end).trimStart(*SEPARATORS)
            if (fraction != null && previousToken(before) in AUDIO_CONTEXT) return@forEach
            if (isNonEpisodeContext(before, after)) return@forEach

            val positionScore = when {
                after.isEmpty() -> 58
                after.matches(ONLY_TAGS) -> 54
                representsEndOfTitle(base, end) -> 48
                else -> 30
            }
            add(
                value = value,
                fraction = fraction,
                endValue = null,
                season = null,
                kind = EpisodeKind.REGULAR,
                version = version,
                score = positionScore,
                start = start,
                source = "number",
            )
        }

        val season = extractSeason(base)
        val best = candidates
            .map { candidate -> candidate.copy(season = candidate.season ?: season) }
            .filter { it.score >= MIN_SCORE }
            .maxWithOrNull(
                compareBy<Candidate> { it.score }
                    .thenBy { it.start }
                    .thenBy { it.value }
                    .thenBy { it.endValue ?: it.value },
            )
            ?: return null

        // A version suffix immediately after the marker is more reliable than a version captured
        // by a broader pattern (for example `[Group] Show - 05 v2`).
        val version = best.version.takeIf { it > 0 } ?: 0

        return ParsedEpisode(
            value = best.value,
            endValue = best.endValue,
            season = best.season,
            kind = best.kind,
            version = version,
            confidence = best.score,
            source = best.source,
        )
    }

    fun parseNumber(fileName: String): Int? = parse(fileName)?.number

    /**
     * Season key carried by the file itself. Folder analysis adds context; this function only
     * reports what the name proves. `SP1` -> `SP`, `OVA1` -> `OVA`, `S02E01` -> `S2`,
     * `Part 2` -> `Part 2`.
     */
    fun seasonKey(fileName: String): String? {
        val base = MediaText.baseName(fileName)
        if (base.isBlank()) return null

        // Only a numbered special (`SP1`, `OVA2`, `Extras 3`) proves a season from a file name.
        // A bare `movie.mkv` is classified as a movie or `Other`, not as a season called "Movie".
        specialEpisodeKey(base)?.let { return it }

        extractSeason(base)?.let { return "S$it" }

        partNumber(base)?.let { return "Part $it" }
        courNumber(base)?.let { return "Cour $it" }
        return null
    }

    private fun specialEpisodeKey(text: String): String? {
        val base = MediaText.baseName(text)
        val raw = SPECIAL_EPISODE.find(base)?.groupValues?.get(1) ?: return null
        return when (raw.uppercase(Locale.ROOT)) {
            "SP", "SPECIAL", "SPECIALS" -> "SP"
            "OVA", "OAV", "OAD" -> "OVA"
            "ONA" -> "ONA"
            else -> "Extras"
        }
    }

    fun extractSeason(text: String): Int? {
        val base = MediaText.baseName(text)

        SEASON_EPISODE.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        CROSS_SEASON_EPISODE.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_WORD.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_ORDINAL.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_ROMAN.find(base)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?.let { ROMAN_SEASON_NUMBERS[it] }?.let { return it }
        SEASON_CJK.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_KOREAN.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_BARE.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun partNumber(text: String): Int? = PART_WORD.find(MediaText.baseName(text))?.groupValues?.get(1)?.toIntOrNull()

    fun courNumber(text: String): Int? = COUR_WORD.find(MediaText.baseName(text))?.groupValues?.get(1)?.toIntOrNull()

    /** Number after a special label (`OVA 2`, `SP1`), if the name carries one. */
    fun specialNumber(text: String): Int? = SPECIAL_EPISODE.find(MediaText.baseName(text))
        ?.groupValues?.get(2)?.toIntOrNull()

    /** Canonical special label for a folder or file name, if it carries one. */
    fun specialLabel(text: String): String? {
        val base = MediaText.baseName(text)
        val raw = SPECIAL_LABEL.find(base)?.groupValues?.get(1) ?: return null
        return when (raw.uppercase(Locale.ROOT)) {
            "SP", "SPECIAL", "SPECIALS" -> "SP"
            "OVA", "OAV", "OAD" -> "OVA"
            "ONA" -> "ONA"
            "MOVIE", "FILM", "GEKIJOUBAN", "劇場版", "剧场版", "映画" -> "Movie"
            else -> "Extras"
        }
    }

    /**
     * Removes an episode suffix from a title, used before metadata lookups.
     * `Show - 01 [1080p]` becomes `Show`, while `Mob Psycho 100` is left intact unless the caller
     * explicitly says the name came from a video file.
     */
    fun stripEpisodeSuffix(name: String, fromFileName: Boolean = false): String {
        var cleaned = MediaText.collapseWhitespace(MediaText.normalizeUnicode(name))
        if (cleaned.isBlank()) return cleaned

        val withoutExplicit = EXPLICIT_SUFFIX.replace(cleaned, " ")
        if (withoutExplicit != cleaned) {
            cleaned = withoutExplicit
        } else if (fromFileName) {
            // A bare trailing number is only an episode marker for actual file names.
            cleaned = BARE_NUMBER_SUFFIX.replace(cleaned, " ")
        }

        cleaned = MediaText.collapseWhitespace(cleaned)
        return MediaText.trimSeparators(cleaned).ifBlank { name.trim() }
    }

    fun format(value: Double, endValue: Double? = null, kind: EpisodeKind = EpisodeKind.REGULAR): String {
        val head = when (kind) {
            EpisodeKind.SPECIAL -> "SP${formatNumber(value)}"
            EpisodeKind.DECIMAL -> "E${formatDecimal(value)}"
            EpisodeKind.REGULAR -> "E${formatNumber(value)}"
        }
        return if (endValue != null && endValue > value) {
            val tail = when (kind) {
                EpisodeKind.SPECIAL -> "SP${formatNumber(endValue)}"
                EpisodeKind.DECIMAL -> "E${formatDecimal(endValue)}"
                EpisodeKind.REGULAR -> "E${formatNumber(endValue)}"
            }
            "$head-$tail"
        } else {
            head
        }
    }

    private fun formatNumber(value: Double): String {
        val integer = value.toInt()
        return if (value == integer.toDouble()) {
            if (integer < 100) String.format(Locale.US, "%02d", integer) else integer.toString()
        } else {
            formatDecimal(value)
        }
    }

    private fun formatDecimal(value: Double): String {
        val text = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
        val parts = text.split('.', limit = 2)
        val integer = (parts.getOrNull(0)?.toIntOrNull() ?: value.toInt()).toString().padStart(2, '0')
        return if (parts.size == 1) integer else "$integer.${parts[1]}"
    }

    private fun fractionToDecimal(fraction: Int?): Double {
        if (fraction == null || fraction <= 0) return 0.0
        val digits = fraction.toString()
        return digits.toDouble() / Math.pow(10.0, digits.length.toDouble())
    }

    private fun plausible(value: Int, fraction: Int?, explicit: Boolean): Boolean {
        if (value < 0 || value > 9999) return false
        if (value in 1900..2099) return false
        if (!explicit) {
            if (value in RESOLUTIONS) return false
        }
        if (fraction != null && fraction > 0) {
            // 5.1 and 7.1 are audio channels, not episodes. Explicit markers (`E05.1`) win.
            if (!explicit && fraction == 1 && value in 5..7) return false
        }
        return true
    }

    private fun isNonEpisodeContext(before: String, after: String): Boolean {
        val previous = previousToken(before)
        if (previous in PART_CONTEXT) return true
        if (before.endsWith("x", ignoreCase = true) || before.endsWith("×")) return true

        val lowerAfter = after.lowercase(Locale.ROOT)
        if (lowerAfter.startsWith("p") && lowerAfter.getOrNull(1)?.isDigit() != true) return true
        if (lowerAfter.startsWith("i") && lowerAfter.getOrNull(1)?.isDigit() != true) return true
        if (lowerAfter.startsWith("fps")) return true
        if (lowerAfter.startsWith("bit")) return true
        if (lowerAfter.startsWith("bps")) return true
        if (lowerAfter.startsWith("kbps") || lowerAfter.startsWith("mbps")) return true
        if (lowerAfter.startsWith("mb") || lowerAfter.startsWith("gb") || lowerAfter.startsWith("kb")) return true
        if (lowerAfter.startsWith("hz")) return true
        if (lowerAfter.startsWith("ch")) return true
        return false
    }

    private fun previousToken(before: String): String = before.substringAfterLast(' ')
        .substringAfterLast('_')
        .substringAfterLast('-')
        .lowercase(Locale.ROOT)

    private fun looksLikeDate(base: String, start: Int, end: Int): Boolean {
        if (DATE_PATTERN.containsMatchIn(base)) {
            val dateRanges = DATE_PATTERN.findAll(base).map { it.range }
            if (dateRanges.any { start >= it.first && end <= it.last + 1 }) return true
        }
        val after = base.substring(end)
        return after.startsWith("-") && after.drop(1).takeWhile { it.isDigit() }.length >= 2 &&
            base.substring(start, end).dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.length <= 2
    }

    /** True when only release tags and separators follow [end], so the number is the last clue. */
    private fun representsEndOfTitle(base: String, end: Int): Boolean {
        val rest = base.substring(end)
        return rest.isBlank() || rest.matches(TAIL_TAGS)
    }

    private data class Candidate(
        val value: Double,
        val endValue: Double?,
        val season: Int?,
        val kind: EpisodeKind,
        val version: Int,
        val score: Int,
        val start: Int,
        val source: String,
    )

    private const val MIN_SCORE = 30
    private val SEPARATORS = charArrayOf(' ', '.', '_', '-', '–', '—', '+', '|', '/', '\\', '(', ')', '[', ']')
    private val ONLY_TAGS = Regex(
        "(?i)(?:[\\s._\\[\\](){}_-]|\\d{3,4}p|\\d{3,4}i|4k|8k|web[- ]?dl|web|blu[- ]?ray|bd|" +
            "h\\.?26[45]|x\\.?26[45]|hevc|avc|aac|ac3|dts|flac|opus|hdr|10bit|8bit|" +
            "multi|dual|sub|subs|dub|v\\d+)*",
    )
    private val TAIL_TAGS = Regex(
        "(?i)[\\s._\\[\\](){}_-]*(?:(?:\\d{3,4}[pi]|4k|8k|web[- ]?dl|web|blu[- ]?ray|bd|" +
            "h\\.?26[45]|x\\.?26[45]|hevc|avc|aac|ac3|dts|flac|opus|hdr|10bit|8bit|" +
            "multi|dual|sub|subs|dub|v\\d+)[\\s._\\[\\](){}_-]*)*",
    )
    private val EXPLICIT_SUFFIX = Regex(
        "(?i)(?:[\\s._-]*(?:" +
            "S\\d{1,2}[\\s._-]*E?\\d{1,4}|" +
            "(?:E|EP|ÉP|Episode|Episodio|Episódio|Épisode|Folge|Odcinek|Odc|Bölüm|Böl|Cap|" +
            "Capítulo|Tập|Сери[яи]|Эпизод|에피소드|エピソード)\\s*[._#:-]?\\s*\\d{1,4}" +
            ")(?:\\.\\d{1,2})?(?:v\\d{1,2})?(?:\\s*[-~–—+]\\s*(?:E|EP)?\\s*\\d{1,4})?\\s*)$|" +
            "(?i)(?:[\\s._-]*(?:第\\s*\\d{1,4}\\s*[话話集回]|\\d{1,4}\\s*(?:话|話|集|回|화|회))\\s*)$|" +
            "(?i)(?:[\\s._-]*[\\[(]\\s*(?:E|EP|Episode|第)?\\s*\\d{1,3}(?:\\.\\d{1,2})?" +
            "(?:v\\d{1,2})?\\s*[\\])]\\s*)$",
    )
    private val BARE_NUMBER_SUFFIX = Regex("(?i)(?:[-–—_]\\s*\\d{1,4}|[\\s._]\\d{1,2})(?:\\.\\d{1,2})?(?:v\\d{1,2})?\\s*$")
}
