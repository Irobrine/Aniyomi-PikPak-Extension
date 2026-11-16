package eu.kanade.tachiyomi.animeextension.all.pikpak

import java.util.Locale

internal enum class DirectoryRole {
    RELEASE_GROUP,
    SEASON,
    PART,
    COUR,
    DISC,
    SPECIAL,
    YEAR,
    GENERIC,
    TITLE,
    UNKNOWN,
}

internal data class AnalyzedDirectory(
    val name: String,
    val role: DirectoryRole,
    val number: Int? = null,
)

internal data class PathAnalysis(
    val folders: List<AnalyzedDirectory>,
    val seriesName: String? = null,
    val seasonLabel: String? = null,
    val seasonNumber: Int? = null,
    val specialLabel: String? = null,
    val discLabel: String? = null,
    val confidence: Double = 0.0,
) {
    val isSpecial: Boolean get() = specialLabel != null
}

/**
 * Reads the folder context around one video file.
 *
 * The old implementation treated the first non-quality folder as a season, which turned
 * `Show/Disc 1/E01.mkv` into season `Disc 1` and collapsed `Collection/Show A/Season 1` into a
 * single ambiguous season. This analyser classifies every folder segment, ignores release groups,
 * discs and generic containers, and can qualify a season with the series folder when several
 * series share one parent.
 */
internal object DirectoryAnalyzer {
    private val ROOT_FOLDERS = setOf(
        "my pack", "my shared", "shared", "drive", "root", "my files", "files", "media library",
        "library", "anime", "anime series", "tv shows", "tv", "movies", "films", "series",
        "downloads", "download", "new folder",
    )

    private val CONTAINER_WORDS = setOf(
        "collection", "collections", "library", "catalog", "folder", "folders", "shows",
        "series", "anime", "tv", "media", "drive", "pack", "downloads", "movies", "films",
        "archive",
    )

    private val SEASON_WORDS = setOf(
        "season", "saison", "staffel", "temporada", "sezon", "seizoen", "stagione", "säsong",
        "sesong", "kausi", "сезон", "시즌", "phần", "musim", "part", "partie", "parte", "teil",
        "часть", "parça", "cour", "クール",
    )

    private val SEASON_PATTERNS = listOf(
        Regex(
            "(?i)^(?:season|saison|staffel|temporada|sezon|seizoen|stagione|säsong|sesong|kausi|" +
                "сезон|시즌|phần|musim)\\s*#?\\s*(\\d{1,3})(?:\\s*(?:part|partie|parte|teil|" +
                "часть|phần|parça)\\s*#?\\s*(\\d{1,2}))?$",
        ),
        Regex("^第\\s*(\\d{1,3})\\s*[季期](?:\\s*第\\s*(\\d{1,2})\\s*[部章])?$"),
        Regex("^(?:제\\s*)?(\\d{1,3})\\s*기$"),
        Regex("(?i)^(\\d{1,2})(?:st|nd|rd|th)\\s+season$"),
        Regex("(?i)^S(\\d{1,2})$"),
        Regex("(?i)^(\\d{1,2})(?:st|nd|rd|th)$"),
    )

    private val SEASON_ROMAN_FOLDER = Regex(
        "(?i)^(?:season|saison|staffel|temporada|sezon)\\s*#?\\s*(ii|iii|iv)$",
    )
    private val ROMAN_SEASON_NUMBERS = mapOf("ii" to 2, "iii" to 3, "iv" to 4)

    private val PART_PATTERNS = listOf(
        Regex(
            "(?i)^(?:part|partie|parte|teil|часть|phần|parça)\\s*#?\\s*(\\d{1,2})$",
        ),
        Regex("^第\\s*(\\d{1,2})\\s*[部章]$"),
    )

    private val COUR_PATTERNS = listOf(
        Regex("(?i)^(?:cour|クール|코트)\\s*#?\\s*(\\d{1,2})$"),
    )

    private val DISC_PATTERN = Regex(
        "(?i)^(?:disc|disk|dvd|cd|blu[- ]?ray|bd|volume|vol\\.?|tome|band)\\s*#?\\s*(\\d{1,3})$",
    )

    private val YEAR_PATTERN = Regex("^(?:19|20)\\d{2}$")
    private val DRIVE_LETTER = Regex("(?i)^[a-z]:$")
    private val SEASON_RANGE = Regex("(?i)^(?:season|s|series|part|cour)\\s*#?\\s*(\\d{1,2})\\s*(?:-|~|–|—|to)\\s*(\\d{1,2})$")
    private val GENERIC_FOLDER = Regex(
        "(?i)^(?:video|videos|episode|episodes|ep|eps|complete|batch|download|downloads|" +
            "sub|subs|subtitle|subtitles|multi|raw|raws|encoded|output|untitled|my pack|my shared|" +
            "shared|drive|root|files|media|library|anime|series|tv|tv shows|movies|films|extras?)$",
    )
    private val RELEASE_GROUP_TAG = Regex("^\\[[^\\]]+]$")

    fun analyzePath(itemName: String, rawPath: String, fallbackName: String): PathAnalysis {
        val segments = splitPath(rawPath, fallbackName)
        val folders = if (segments.size <= 1 && MediaText.hasVideoExtension(segments.lastOrNull().orEmpty())) {
            emptyList()
        } else {
            segments.dropLast(1)
        }

        val itemKey = MediaText.normalizedKey(FilmNameParser.cleanAnimeName(itemName))
        val firstKeep = itemFolderIndex(folders, itemKey, itemName)?.plus(1)
            ?: leadingRootFolders(folders)
        val relative = folders.drop(firstKeep)
            .map(::classify)
            .filterNot { it.role == DirectoryRole.GENERIC }

        return buildAnalysis(itemName, itemKey, relative)
    }

    /**
     * Collective analysis for a whole folder listing. Handles the case where the parent holds
     * several series folders, and where a flat folder has show-name prefixes instead of seasons.
     */
    fun analyzeListing(itemName: String, files: List<Pair<String, String>>): List<PathAnalysis> = files.map { (rawPath, fileName) -> analyzePath(itemName, rawPath, fileName) }

    fun classify(name: String): AnalyzedDirectory {
        val cleaned = FilmNameParser.cleanAnimeName(name)
        val normalized = MediaText.normalizedKey(cleaned)
        if (cleaned.isBlank()) return AnalyzedDirectory(name, DirectoryRole.UNKNOWN)

        if (RELEASE_GROUP_TAG.matches(cleaned.trim())) {
            val inner = cleaned.trim().removeSurrounding("[", "]").trim()
            val innerRole = classify(inner)
            return when (innerRole.role) {
                DirectoryRole.SEASON, DirectoryRole.PART, DirectoryRole.COUR,
                DirectoryRole.DISC, DirectoryRole.SPECIAL, DirectoryRole.GENERIC,
                DirectoryRole.YEAR,
                -> innerRole.copy(name = inner)
                else -> AnalyzedDirectory(cleaned, DirectoryRole.RELEASE_GROUP)
            }
        }

        SEASON_RANGE.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()?.let { start ->
            return AnalyzedDirectory(cleaned, DirectoryRole.SEASON, start)
        }

        SEASON_PATTERNS.forEach { pattern ->
            val match = pattern.find(cleaned) ?: return@forEach
            val number = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (number !in 1900..2099) return AnalyzedDirectory(cleaned, DirectoryRole.SEASON, number)
        }

        SEASON_ROMAN_FOLDER.find(cleaned)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?.let { ROMAN_SEASON_NUMBERS[it] }
            ?.let { return AnalyzedDirectory(cleaned, DirectoryRole.SEASON, it) }

        PART_PATTERNS.forEach { pattern ->
            pattern.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return AnalyzedDirectory(cleaned, DirectoryRole.PART, it)
            }
        }

        COUR_PATTERNS.forEach { pattern ->
            pattern.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return AnalyzedDirectory(cleaned, DirectoryRole.COUR, it)
            }
        }

        DISC_PATTERN.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return AnalyzedDirectory(cleaned, DirectoryRole.DISC, it)
        }

        if (YEAR_PATTERN.matches(cleaned.trim())) {
            return AnalyzedDirectory(cleaned, DirectoryRole.YEAR)
        }

        // Plural "Movies"/"Films" are containers; singular "Movie"/"Film" is a specials folder.
        if (normalized == "movies" || normalized == "films" || normalized == "movie collection") {
            return AnalyzedDirectory(cleaned, DirectoryRole.GENERIC)
        }

        EpisodeParser.specialLabel(cleaned)?.let { label ->
            val roleLabel = if (label.equals("SP", ignoreCase = true) && cleaned.none { it.isDigit() }) {
                "Specials"
            } else {
                cleaned
            }
            return AnalyzedDirectory(roleLabel, DirectoryRole.SPECIAL)
        }

        if (MediaText.isVideoTypeTag(cleaned)) {
            return AnalyzedDirectory(cleaned, DirectoryRole.RELEASE_GROUP)
        }

        if (GENERIC_FOLDER.matches(cleaned.trim())) {
            return AnalyzedDirectory(cleaned, DirectoryRole.GENERIC)
        }

        if (normalized in ROOT_FOLDERS) {
            return AnalyzedDirectory(cleaned, DirectoryRole.GENERIC)
        }
        if (isGeneric(cleaned)) {
            return AnalyzedDirectory(cleaned, DirectoryRole.GENERIC)
        }
        if (MediaText.isNoiseTag(cleaned)) {
            return AnalyzedDirectory(cleaned, DirectoryRole.GENERIC)
        }
        if (SEASON_WORDS.any { normalized == it }) {
            return AnalyzedDirectory(cleaned, DirectoryRole.UNKNOWN)
        }

        return AnalyzedDirectory(cleaned, DirectoryRole.TITLE)
    }

    private fun buildAnalysis(
        itemName: String,
        itemKey: String,
        folders: List<AnalyzedDirectory>,
    ): PathAnalysis {
        if (folders.isEmpty()) return PathAnalysis(folders)

        val season = folders.lastOrNull { it.role == DirectoryRole.SEASON }
        val embeddedPart = season?.name?.let { name ->
            EpisodeParser.partNumber(name)?.let { AnalyzedDirectory("Part $it", DirectoryRole.PART, it) }
        }
        val embeddedCour = season?.name?.let { name ->
            EpisodeParser.courNumber(name)?.let { AnalyzedDirectory("Cour $it", DirectoryRole.COUR, it) }
        }
        val part = folders.lastOrNull { it.role == DirectoryRole.PART } ?: embeddedPart
        val cour = folders.lastOrNull { it.role == DirectoryRole.COUR } ?: embeddedCour
        val disc = folders.lastOrNull { it.role == DirectoryRole.DISC }
        val special = folders.lastOrNull { it.role == DirectoryRole.SPECIAL }
        val titles = folders.filter { it.role == DirectoryRole.TITLE }

        // A title folder before the season is a series qualifier when the parent is a container.
        val seriesFolder = titles.lastOrNull {
            val key = MediaText.normalizedKey(it.name)
            key.isNotBlank() && key != itemKey
        }

        if (special != null) {
            val label = if (season != null && season.name != special.name) {
                "${season.name} ${special.name}"
            } else {
                special.name
            }
            return PathAnalysis(
                folders = folders,
                seriesName = seriesFolder?.name,
                seasonLabel = label,
                seasonNumber = FilmNameParser.detectedSeasonNumber(season?.name.orEmpty()),
                specialLabel = EpisodeParser.specialLabel(special.name) ?: "SP",
                discLabel = disc?.name,
                confidence = 0.9,
            )
        }

        if (season != null) {
            val qualifier = seriesFolder
            val seasonLower = season.name.lowercase(Locale.ROOT)
            val base = when {
                part != null && part.name.isNotBlank() && !seasonLower.contains("part") ->
                    "${season.name} ${part.name}"
                cour != null && cour.name.isNotBlank() && !seasonLower.contains("cour") ->
                    "${season.name} ${cour.name}"
                else -> season.name
            }
            val label = if (qualifier != null) "${qualifier.name} - $base" else base
            return PathAnalysis(
                folders = folders,
                seriesName = qualifier?.name ?: seriesFolder?.name,
                seasonLabel = label,
                seasonNumber = FilmNameParser.detectedSeasonNumber(base),
                discLabel = disc?.name,
                confidence = if (qualifier != null) 0.92 else 1.0,
            )
        }

        if (part != null || cour != null) {
            val base = part?.name ?: cour?.name
            val qualifier = seriesFolder?.takeIf { base != null && it.name != base }
            val label = if (qualifier != null) "${qualifier.name} - $base" else base
            return PathAnalysis(
                folders = folders,
                seriesName = qualifier?.name ?: seriesFolder?.name,
                seasonLabel = label,
                seasonNumber = FilmNameParser.detectedSeasonNumber(base.orEmpty()),
                discLabel = disc?.name,
                confidence = 0.82,
            )
        }

        if (titles.isNotEmpty()) {
            // A free-form season such as `Dear Sister`, or a series folder under a container.
            val title = titles.first()
            return PathAnalysis(
                folders = folders,
                seriesName = title.name.takeIf {
                    isContainerName(itemName) && MediaText.normalizedKey(it) != itemKey
                },
                seasonLabel = title.name,
                seasonNumber = null,
                discLabel = disc?.name,
                confidence = 0.65,
            )
        }

        return PathAnalysis(
            folders = folders,
            discLabel = disc?.name,
            confidence = if (disc != null) 0.4 else 0.2,
        )
    }

    private fun splitPath(rawPath: String, fallbackName: String): List<String> {
        val normalized = MediaText.normalizeUnicode(rawPath)
            .replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "." }
            .filterNot { it == ".." || DRIVE_LETTER.matches(it) }

        if (normalized.isEmpty()) return listOf(fallbackName)
        val last = normalized.last()
        return if (!MediaText.isVideoFile(last) && last != fallbackName && fallbackName.isNotBlank()) {
            normalized + fallbackName
        } else {
            normalized
        }
    }

    private fun itemFolderIndex(folders: List<String>, itemKey: String, itemName: String): Int? {
        if (itemKey.isBlank()) return null
        val itemMetaKey = MediaText.normalizedKey(FilmNameParser.metadataSearchTitle(itemName))
        for (index in folders.indices) {
            val cleaned = FilmNameParser.cleanAnimeName(folders[index])
            val key = MediaText.normalizedKey(cleaned)
            val metaKey = MediaText.normalizedKey(FilmNameParser.metadataSearchTitle(folders[index]))
            val matches = key == itemKey ||
                (itemMetaKey.isNotBlank() && metaKey == itemMetaKey)
            if (matches && !isSeasonLike(folders[index])) return index
        }
        return null
    }

    private fun leadingRootFolders(folders: List<String>): Int {
        var index = 0
        while (index < folders.size) {
            val key = MediaText.normalizedKey(FilmNameParser.cleanAnimeName(folders[index]))
            if (key in ROOT_FOLDERS) {
                index++
            } else {
                break
            }
        }
        return index
    }

    private fun isSeasonLike(name: String): Boolean {
        val role = classify(name).role
        return role == DirectoryRole.SEASON || role == DirectoryRole.PART || role == DirectoryRole.COUR ||
            role == DirectoryRole.SPECIAL || role == DirectoryRole.DISC
    }

    private fun isGeneric(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("new folder") ||
            lower.matches(Regex("(?i)untitled(?:\\s*\\d+)?")) ||
            lower.matches(Regex("(?i)(?:disk|disc)\\s*\\d+\\s*(?:extras?)?"))
    }

    private fun isContainerName(name: String): Boolean {
        val tokens = MediaText.tokens(name)
        return tokens.any { it in CONTAINER_WORDS }
    }
}
