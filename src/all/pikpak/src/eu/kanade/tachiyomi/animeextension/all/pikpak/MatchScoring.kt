package eu.kanade.tachiyomi.animeextension.all.pikpak

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * Candidate model shared by AniList and TMDB.
 *
 * The providers return a list now, not just their first guess. Keeping the provider payload in
 * [value] lets the client rank everything before it commits to a match.
 */
internal data class MetadataCandidate<T>(
    val key: String,
    val titles: List<String>,
    val year: Int? = null,
    val format: String? = null,
    val isAdult: Boolean = false,
    val popularity: Double = 0.0,
    val seasonNumber: Int? = null,
    val value: T,
)

internal data class ScoredCandidate<T>(
    val candidate: MetadataCandidate<T>,
    val score: Double,
    val titleScore: Double,
    val reasons: List<String>,
)

/**
 * Guards metadata lookups against confident-but-wrong API results.
 *
 * AniList matches on free text, so a folder called `Monster` can resolve to an unrelated release
 * with the same name. A result is accepted only when its year is compatible and one of its titles
 * matches the query closely enough. Multiple candidates are ranked, so the best release for a
 * season or movie can be chosen instead of trusting the provider's first hit.
 */
internal object MatchScoring {
    private const val MIN_TITLE_SCORE = 0.55
    private const val MIN_MATCH_SCORE = 0.76
    private const val YEAR_TOLERANCE = 1
    private const val RANK_YEAR_TOLERANCE = 2

    private val SEASON_NOISE = setOf(
        "season", "saison", "staffel", "temporada", "sezon", "seizoen", "stagione", "kausi",
        "part", "partie", "parte", "teil", "cour", "specials", "special", "ova", "oav", "oad",
        "ona", "movie", "film", "complete", "batch", "uncut", "uncensored", "remastered",
    )

    fun isPlausible(
        query: String,
        year: Int?,
        titles: List<String>,
        seasonYear: Int?,
        isAdult: Boolean,
    ): Boolean {
        if (isAdult) return false
        if (titles.isEmpty()) return false
        if (year != null && seasonYear != null && abs(year - seasonYear) > YEAR_TOLERANCE) {
            return false
        }
        return titles.any { titleMatches(query, it) }
    }

    fun titleMatches(query: String, candidate: String): Boolean {
        val left = MediaText.normalizedKey(query)
        val right = MediaText.normalizedKey(candidate)
        if (left.isBlank() || right.isBlank()) return left == right
        return scoreTitle(query, candidate).score >= MIN_TITLE_SCORE
    }

    /**
     * Ranks all provider candidates. The returned list only contains matches that passed the
     * confidence and metadata compatibility checks; callers should use the first entry or treat
     * an empty list as "no confident match".
     */
    fun <T> rank(
        query: String,
        year: Int? = null,
        preferredFormat: String? = null,
        seasonNumber: Int? = null,
        candidates: List<MetadataCandidate<T>>,
        minimumScore: Double = MIN_MATCH_SCORE,
    ): List<ScoredCandidate<T>> {
        if (query.isBlank() || candidates.isEmpty()) return emptyList()

        return candidates
            .mapNotNull { candidate ->
                score(query, year, preferredFormat, seasonNumber, candidate)
                    ?.takeIf { it.score >= minimumScore }
            }
            .sortedWith(
                compareByDescending<ScoredCandidate<T>> { it.score }
                    .thenByDescending { it.titleScore }
                    .thenByDescending { it.candidate.popularity }
                    .thenBy { it.candidate.key },
            )
    }

    fun <T> best(
        query: String,
        year: Int? = null,
        preferredFormat: String? = null,
        seasonNumber: Int? = null,
        candidates: List<MetadataCandidate<T>>,
        minimumScore: Double = MIN_MATCH_SCORE,
    ): ScoredCandidate<T>? = rank(query, year, preferredFormat, seasonNumber, candidates, minimumScore).firstOrNull()

    private fun <T> score(
        query: String,
        year: Int?,
        preferredFormat: String?,
        seasonNumber: Int?,
        candidate: MetadataCandidate<T>,
    ): ScoredCandidate<T>? {
        if (candidate.isAdult) return null

        if (year != null && candidate.year != null && abs(year - candidate.year) > RANK_YEAR_TOLERANCE) {
            return null
        }

        val title = candidate.titles
            .filter { it.isNotBlank() }
            .maxOfOrNull { scoreTitle(query, it).score }
            ?: return null
        if (title < MIN_TITLE_SCORE) return null

        val reasons = mutableListOf<String>()
        var total = title
        reasons += "title=%.2f".format(Locale.US, title)

        if (year != null && candidate.year != null) {
            when (abs(year - candidate.year)) {
                0 -> {
                    total += 0.12
                    reasons += "year=exact"
                }
                1 -> {
                    total += 0.02
                    reasons += "year=near"
                }
                else -> {
                    total -= 0.12
                    reasons += "year=far"
                }
            }
        }

        val preferred = normalizeFormat(preferredFormat)
        val actual = normalizeFormat(candidate.format)
        if (preferred != null && actual != null) {
            when {
                preferred == actual -> {
                    total += 0.06
                    reasons += "format=exact"
                }
                preferred == "TV" && actual == "TV_SHORT" -> {
                    total += 0.02
                    reasons += "format=short"
                }
                preferred == "TV_SHORT" && actual == "TV" -> total -= 0.03
                preferred == "TV" && actual in setOf("OVA", "ONA", "SPECIAL") -> total -= 0.06
                preferred == "MOVIE" && actual == "TV" -> total -= 0.12
                preferred == "TV" && actual == "MOVIE" -> total -= 0.18
                else -> total -= 0.04
            }
        }

        if (seasonNumber != null) {
            val candidateSeason = candidate.seasonNumber
            when {
                seasonNumber <= 0 && titlesIndicateSpecial(candidate.titles) -> {
                    total += 0.08
                    reasons += "season=special"
                }
                seasonNumber <= 0 -> {
                    total -= 0.06
                    reasons += "season=not-special"
                }
                candidateSeason != null && candidateSeason == seasonNumber -> {
                    total += 0.10
                    reasons += "season=exact"
                }
                candidateSeason != null -> {
                    val penalty = if (candidateSeason == 1 || seasonNumber == 1) 0.28 else 0.14
                    total -= penalty
                    reasons += "season=wrong"
                }
                titlesIndicateSeason(candidate.titles, seasonNumber) -> {
                    total += 0.08
                    reasons += "season=hint"
                }
                seasonNumber == 1 && titlesIndicateOtherSeason(candidate.titles, 1) -> {
                    total -= 0.12
                    reasons += "season=wrong"
                }
                seasonNumber > 1 -> {
                    total -= 0.10
                    reasons += "season=unknown"
                }
            }
        }

        if (candidate.popularity > 0.0) {
            total += (ln(1.0 + candidate.popularity) / 100.0).coerceAtMost(0.05)
        }

        return ScoredCandidate(
            candidate = candidate,
            score = total,
            titleScore = title,
            reasons = reasons,
        )
    }

    /** Title-only score in the 0..1 range. */
    fun scoreTitle(query: String, candidate: String): ScoredCandidate<String> {
        val queryKey = MediaText.normalizedKey(query)
        val candidateKey = MediaText.normalizedKey(candidate)
        if (queryKey.isBlank() || candidateKey.isBlank()) {
            return ScoredCandidate(
                candidate = MetadataCandidate(key = "title", titles = listOf(candidate), value = candidate),
                score = 0.0,
                titleScore = 0.0,
                reasons = listOf("blank"),
            )
        }
        if (queryKey == candidateKey) {
            return ScoredCandidate(
                candidate = MetadataCandidate(key = "title", titles = listOf(candidate), value = candidate),
                score = 1.0,
                titleScore = 1.0,
                reasons = listOf("exact"),
            )
        }

        val core = coreTokens(query).ifEmpty { MediaText.tokens(query) }
        val candidateTokens = MediaText.tokens(candidate)
        val coreTokens = core.toSet()
        val candidateSet = candidateTokens.toSet()

        var best = 0.0
        val allPresent = coreTokens.isNotEmpty() && coreTokens.all { it in candidateSet }
        if (allPresent) {
            val extra = (candidateSet - coreTokens).count { !isSeasonNoiseToken(it) }
            val singleWordPenalty = if (coreTokens.size == 1 && extra > 0) 0.12 else 0.0
            val prefixBonus = if (candidateTokens.take(coreTokens.size) == core) 0.05 else 0.0
            val lengthPenalty = (extra * 0.03).coerceAtMost(0.15)
            best = maxOf(best, 0.88 - singleWordPenalty - lengthPenalty + prefixBonus)
        }

        val candidateSubset = candidateSet.isNotEmpty() && candidateSet.all { it in coreTokens }
        if (candidateSubset) {
            val extra = (coreTokens - candidateSet).size
            best = maxOf(best, 0.84 - (extra * 0.04).coerceAtMost(0.2))
        }

        val compactQuery = MediaText.compactKey(core.joinToString(" "))
        val compactCandidate = MediaText.compactKey(candidate)
        if (compactQuery.isNotBlank() && compactCandidate.isNotBlank()) {
            val longer = maxOf(compactQuery.length, compactCandidate.length)
            val shorter = minOf(compactQuery.length, compactCandidate.length)
            if (longer > 0 && shorter.toDouble() / longer >= 0.6 &&
                (compactQuery.startsWith(compactCandidate) || compactCandidate.startsWith(compactQuery))
            ) {
                best = maxOf(best, 0.86)
            }
            best = maxOf(best, similarity(compactQuery, compactCandidate) * 0.95)
        }

        return ScoredCandidate(
            candidate = MetadataCandidate(key = "title", titles = listOf(candidate), value = candidate),
            score = best,
            titleScore = best,
            reasons = listOf(if (best > 0) "fuzzy" else "unrelated"),
        )
    }

    fun normalize(value: String): String = MediaText.compactKey(value)

    fun tokens(value: String): List<String> = MediaText.tokens(value)

    /** Season number carried by a provider title (`Show 2nd Season`, `Show Season 2`, `Part 2`). */
    fun titleSeason(titles: List<String>): Int? = titles.asSequence()
        .mapNotNull { title ->
            EpisodeParser.specialLabel(title)?.let { EpisodeParser.specialNumber(title) ?: 0 }
                ?: EpisodeParser.extractSeason(title)
                ?: EpisodeParser.partNumber(title)
                ?: EpisodeParser.courNumber(title)
                ?: ROMAN_SEASON.find(title)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { ROMAN_SEQUEL_TOKENS.indexOf(it) + 2 }
        }
        .firstOrNull()

    /** Dice coefficient over character bigrams. */
    fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.length < 2 || right.length < 2) return 0.0

        val leftPairs = left.windowed(2).toSet()
        val rightPairs = right.windowed(2).toSet()
        val overlap = leftPairs.count { it in rightPairs }
        return 2.0 * overlap / (leftPairs.size + rightPairs.size)
    }

    private fun coreTokens(query: String): List<String> {
        val tokens = MediaText.tokens(query)
        val result = mutableListOf<String>()
        var skipNumber = false
        tokens.forEach { token ->
            if (token in SEASON_NOISE) {
                skipNumber = true
                return@forEach
            }
            if (skipNumber && token.all { it.isDigit() }) {
                skipNumber = false
                return@forEach
            }
            skipNumber = false
            if (token !in setOf("the", "a", "an")) result += token
        }
        return result
    }

    private fun isSeasonNoiseToken(token: String): Boolean = token in SEASON_NOISE ||
        token in STOP_WORDS ||
        token.all { it.isDigit() } ||
        token.matches(ORDINAL_TOKEN) ||
        token in ROMAN_SEQUEL_TOKENS

    private fun titlesIndicateSeason(titles: List<String>, number: Int): Boolean = titles.any { title ->
        val digits = number.toString()
        val ordinalWord = ordinal(number)
        val tokens = MediaText.tokens(title)
        tokens.windowed(2).any { pair ->
            (pair[0] == "season" && pair[1] == digits) ||
                (pair[0] == digits && pair[1] == "season") ||
                (pair[0] == "part" && pair[1] == digits) ||
                (pair[0] == "cour" && pair[1] == digits) ||
                (pair[0] == "season" && pair[1] == ordinalWord) ||
                (pair[0] == ordinalWord && pair[1] == "season")
        } ||
            ROMAN_SEQUELS[number]?.let { tokens.contains(it.trim()) } == true
    }

    private fun ordinal(number: Int): String {
        val suffix = when {
            number % 100 in 11..13 -> "th"
            number % 10 == 1 -> "st"
            number % 10 == 2 -> "nd"
            number % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$number$suffix"
    }

    private fun titlesIndicateOtherSeason(titles: List<String>, number: Int): Boolean = (2..12).any { other -> other != number && titlesIndicateSeason(titles, other) }

    private fun titlesIndicateSpecial(titles: List<String>): Boolean = titles.any {
        val normalized = MediaText.normalizedKey(it)
        SPECIAL_WORDS.any { word -> normalized.contains(word) }
    }

    private fun normalizeFormat(format: String?): String? {
        val value = format?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (value.isEmpty()) return null
        return when (value) {
            "TV", "TV SERIES", "SERIES" -> "TV"
            "TV_SHORT", "TV SHORT", "SHORT" -> "TV_SHORT"
            "MOVIE", "FILM" -> "MOVIE"
            "OVA", "OAV", "OAD" -> "OVA"
            "ONA" -> "ONA"
            "SPECIAL", "SPECIALS" -> "SPECIAL"
            "MUSIC" -> "MUSIC"
            else -> value
        }
    }

    private val SPECIAL_WORDS = listOf("special", "ova", "ona", "extra", "bonus", "movie")
    private val STOP_WORDS = setOf("the", "a", "an")
    private val ORDINAL_TOKEN = Regex("\\d{1,3}(?:st|nd|rd|th)")
    private val ROMAN_SEQUEL_TOKENS = listOf("ii", "iii", "iv", "second", "third", "fourth")
    private val ROMAN_SEASON = Regex("(?i)(?<![\\p{L}\\p{N}])(ii|iii|iv)(?![\\p{L}\\p{N}])")
    private val ROMAN_SEQUELS = mapOf(
        2 to " ii",
        3 to " iii",
        4 to " iv",
    )
}
