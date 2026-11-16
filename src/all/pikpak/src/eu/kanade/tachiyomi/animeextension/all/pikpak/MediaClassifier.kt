package eu.kanade.tachiyomi.animeextension.all.pikpak

import java.util.Locale

internal enum class MediaKind {
    SERIES,
    SEASON,
    EPISODE,
    MOVIE,
    SPECIAL,
    OVA,
    ONA,
    SHORT,
    OTHER,
}

internal data class MediaClassification(
    val kind: MediaKind,
    val confidence: Double,
    val reason: String,
)

/**
 * Classifies a folder or single file before it is turned into an [SAnime].
 *
 * The classification is intentionally conservative: a single unmarked video is reported as a
 * movie with low confidence, while conflicting evidence (a movie folder containing several
 * episodes, or a series folder with no episodes) falls back to [MediaKind.SERIES] instead of
 * hiding entries.
 */
internal object MediaClassifier {
    fun classifyCatalog(name: String, isFolder: Boolean, isTorrent: Boolean, isVideo: Boolean): MediaClassification {
        if (isFolder) {
            specialKind(name)?.let { return MediaClassification(it, 0.85, "folder name") }
            if (isShortName(name)) return MediaClassification(MediaKind.SHORT, 0.8, "folder name")
            if (isMovieName(name)) return MediaClassification(MediaKind.MOVIE, 0.8, "movie folder")
            return MediaClassification(MediaKind.SERIES, 0.8, "folder")
        }
        if (!isVideo) {
            return MediaClassification(MediaKind.OTHER, 0.9, "not a video")
        }
        if (isTorrent) {
            return MediaClassification(MediaKind.MOVIE, 0.55, "single torrent file")
        }
        specialKind(name)?.let { kind ->
            return MediaClassification(kind, 0.85, "name")
        }
        EpisodeParser.parse(name)?.let {
            return MediaClassification(MediaKind.EPISODE, 0.75, "episode marker")
        }
        if (isMovieName(name)) {
            return MediaClassification(MediaKind.MOVIE, 0.8, "movie name")
        }
        if (isShortName(name)) {
            return MediaClassification(MediaKind.SHORT, 0.8, "short name")
        }
        return MediaClassification(MediaKind.MOVIE, 0.5, "single video")
    }

    fun classifyLibrary(
        itemName: String,
        isFolder: Boolean,
        fileNames: List<String>,
        seasons: List<String>,
    ): MediaClassification {
        if (!isFolder) {
            val single = fileNames.firstOrNull().orEmpty()
            specialKind(single)?.let { return MediaClassification(it, 0.85, "file name") }
            if (isShortName(single)) return MediaClassification(MediaKind.SHORT, 0.75, "short")
            EpisodeParser.parse(single)?.let { return MediaClassification(MediaKind.EPISODE, 0.75, "episode marker") }
            if (isMovieName(single)) return MediaClassification(MediaKind.MOVIE, 0.85, "movie name")
            return MediaClassification(MediaKind.MOVIE, 0.6, "single file")
        }

        if (fileNames.isEmpty()) return MediaClassification(MediaKind.OTHER, 0.9, "no videos")

        val nonBlankSeasons = seasons.filter { it.isNotBlank() }
        if (nonBlankSeasons.isNotEmpty()) {
            val specialSeasons = nonBlankSeasons.filter { FilmNameParser.isSpecialSeason(it) }
            if (specialSeasons.size == nonBlankSeasons.size && nonBlankSeasons.isNotEmpty()) {
                val label = specialSeasons.first()
                val kind = when (EpisodeParser.specialLabel(label)?.uppercase(Locale.ROOT)) {
                    "OVA" -> MediaKind.OVA
                    "ONA" -> MediaKind.ONA
                    "MOVIE" -> MediaKind.MOVIE
                    else -> MediaKind.SPECIAL
                }
                return MediaClassification(kind, 0.8, "only special folders")
            }
            return MediaClassification(MediaKind.SERIES, 0.95, "season folders")
        }

        specialKind(itemName)?.let { return MediaClassification(it, 0.85, "folder name") }
        if (isShortName(itemName)) return MediaClassification(MediaKind.SHORT, 0.8, "short folder name")

        if (fileNames.size == 1) {
            val only = fileNames.first()
            EpisodeParser.parse(only)?.let { return MediaClassification(MediaKind.EPISODE, 0.7, "single episode") }
            if (isMovieName(itemName) || isMovieName(only)) {
                return MediaClassification(MediaKind.MOVIE, 0.85, "movie folder")
            }
            return MediaClassification(MediaKind.MOVIE, 0.55, "single video folder")
        }

        return MediaClassification(MediaKind.SERIES, 0.9, "multiple videos")
    }

    fun isMovieName(name: String): Boolean = MOVIE_NAME.containsMatchIn(MediaText.normalizeUnicode(name))

    fun isShortName(name: String): Boolean = SHORT_NAME.containsMatchIn(MediaText.normalizeUnicode(name))

    fun specialKind(name: String): MediaKind? = when (EpisodeParser.specialLabel(name)?.uppercase(Locale.ROOT)) {
        "OVA" -> MediaKind.OVA
        "ONA" -> MediaKind.ONA
        "MOVIE" -> MediaKind.MOVIE
        "SP", "EXTRAS" -> MediaKind.SPECIAL
        else -> null
    }

    private val MOVIE_NAME = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:movie|film|gekijouban|the movie|劇場版|剧场版|映画)(?![\\p{L}\\p{N}])",
    )
    private val SHORT_NAME = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:short|pv|cm|spot|teaser|trailer|promo)(?![\\p{L}\\p{N}])",
    )
}

internal object LibraryAnalyzer {
    data class VideoFile(
        val id: String,
        val name: String,
        val path: String,
        val createdTime: String? = null,
        val thumbnail: String? = null,
        val isVideo: Boolean = MediaText.isVideoFile(name) || MediaText.hasVideoExtension(path),
    )

    data class Entry(
        val fileName: String,
        val fileId: String,
        val season: String?,
        val episode: Double?,
        val endEpisode: Double?,
        val kind: EpisodeKind,
        val version: Int,
        val released: String? = null,
        val thumbnail: String? = null,
        val seriesHint: String? = null,
    ) {
        fun toRecord(): EpisodeRecord = EpisodeRecord(
            fileName = fileName,
            fileId = fileId,
            season = season,
            episode = episode,
            endEpisode = endEpisode,
            kind = kind,
            version = version,
            released = released,
            thumbnail = thumbnail,
        )
    }

    data class Analysis(
        val name: String,
        val entries: List<Entry>,
        val seasons: List<String>,
        val mediaKind: MediaKind,
        val classification: MediaClassification,
        val isFolder: Boolean,
        val hasSeasonFolders: Boolean,
        val isFlat: Boolean,
    )

    fun analyze(itemName: String, files: List<VideoFile>, isFolder: Boolean = true): Analysis {
        val unique = files
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .filter { it.isVideo }

        val analyses = DirectoryAnalyzer.analyzeListing(
            itemName,
            unique.map { it.path to it.name },
        )

        val rawEntries = unique.mapIndexed { index, file ->
            val parsed = EpisodeParser.parse(file.name)
            val pathAnalysis = analyses.getOrNull(index)
            Entry(
                fileName = file.name,
                fileId = file.id,
                season = pathAnalysis?.seasonLabel ?: FilmNameParser.seasonKeyFromFileName(file.name),
                episode = parsed?.value,
                endEpisode = parsed?.endValue,
                kind = parsed?.kind ?: EpisodeKind.REGULAR,
                version = parsed?.version ?: 0,
                released = file.createdTime,
                thumbnail = file.thumbnail,
                seriesHint = pathAnalysis?.seriesName,
            )
        }

        val withFileNameSeasons = if (rawEntries.any { it.season != null }) {
            rawEntries
        } else {
            val assigned = FilmNameParser.assignFileNameSeasons(rawEntries.map { it.fileName }, itemName)
            if (assigned.any { it != null }) {
                rawEntries.mapIndexed { index, entry ->
                    assigned[index]?.let { entry.copy(season = it, seriesHint = entry.seriesHint ?: it) } ?: entry
                }
            } else {
                rawEntries
            }
        }

        val seasons = withFileNameSeasons.map { it.season }
        val withOther = FilmNameParser.withOtherSeason(seasons)
        val otherMapped = if (withOther == seasons) {
            withFileNameSeasons
        } else {
            withFileNameSeasons.mapIndexed { index, entry -> entry.copy(season = withOther[index]) }
        }
        val canonical = FilmNameParser.canonicalSeasonLabels(otherMapped.mapNotNull { it.season })
        val entries = otherMapped.map { entry ->
            entry.season?.let { label -> canonical[label]?.let { entry.copy(season = it) } ?: entry } ?: entry
        }

        val seasonLabels = FilmNameParser.orderedSeasons(entries.mapNotNull { it.season })
        val classification = MediaClassifier.classifyLibrary(
            itemName = itemName,
            isFolder = isFolder,
            fileNames = entries.map { it.fileName },
            seasons = seasonLabels,
        )

        return Analysis(
            name = FilmNameParser.cleanAnimeName(itemName),
            entries = entries.sortedWith { left, right -> EpisodeOrdering.compare(left.toRecord(), right.toRecord()) },
            seasons = seasonLabels,
            mediaKind = classification.kind,
            classification = classification,
            isFolder = isFolder,
            hasSeasonFolders = analyses.any { it.seasonLabel != null },
            isFlat = seasonLabels.isEmpty(),
        )
    }
}
