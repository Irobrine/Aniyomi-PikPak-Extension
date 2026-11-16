package eu.kanade.tachiyomi.animeextension.all.pikpak

/** One video after directory analysis, before metadata-assisted renumbering. */
internal data class EpisodeRecord(
    val fileName: String,
    val fileId: String,
    val season: String?,
    val episode: Double?,
    val endEpisode: Double? = null,
    val kind: EpisodeKind = EpisodeKind.REGULAR,
    val version: Int = 0,
    val released: String? = null,
    val thumbnail: String? = null,
)

internal data class NumberedEpisode(
    val record: EpisodeRecord,
    val number: Double,
    val endNumber: Double?,
    val kind: EpisodeKind,
)

/**
 * Semantic ordering for episodes that can mix seasons, specials, decimals and multi-episode files.
 *
 * File-name natural order is used only as the final tie-break. This keeps `E2` before `E10`,
 * `E01-E03` before `E04`, `S00` specials before the main run and alternate releases (`v1`, `v2`)
 * in a deterministic order instead of relying on the drive's arbitrary listing order.
 */
internal object EpisodeOrdering {
    fun sortRecords(records: List<EpisodeRecord>): List<EpisodeRecord> = records.sortedWith(::compare)

    fun compare(left: EpisodeRecord, right: EpisodeRecord): Int {
        val bySeason = compareSeason(left.season, right.season)
        if (bySeason != 0) return bySeason

        val leftNumber = left.episode
        val rightNumber = right.episode
        val byEpisode = when {
            leftNumber == null && rightNumber == null -> 0
            leftNumber == null -> 1
            rightNumber == null -> -1
            else -> leftNumber.compareTo(rightNumber)
        }
        if (byEpisode != 0) return byEpisode

        val byKind = kindRank(left.kind).compareTo(kindRank(right.kind))
        if (byKind != 0) return byKind

        val byEnd = (left.endEpisode ?: left.episode ?: 0.0).compareTo(right.endEpisode ?: right.episode ?: 0.0)
        if (byEnd != 0) return byEnd

        val byVersion = left.version.compareTo(right.version)
        if (byVersion != 0) return byVersion

        val byName = FilmNameParser.naturalCompare(left.fileName, right.fileName)
        if (byName != 0) return byName
        return left.fileId.compareTo(right.fileId)
    }

    /**
     * Numbers episodes inside each season. A flat folder that has no seasons keeps one sequence;
     * a mixed folder with file-name seasons gets a fresh sequence per season.
     */
    fun numberBySeason(
        records: List<EpisodeRecord>,
        metadataNumbers: Map<String, Double?> = emptyMap(),
    ): List<NumberedEpisode> {
        val groups = records.groupBy { MediaText.normalizedKey(it.season.orEmpty()) }

        val numbered = mutableListOf<NumberedEpisode>()
        groups.values.forEach { group ->
            // Numbering follows name order so a missing number is filled where it sits between
            // its neighbours (`E02`, untitled file, `E04`), not where a semantic sort puts it.
            val inNameOrder = group.sortedWith { left, right ->
                FilmNameParser.naturalCompare(left.fileName, right.fileName)
            }
            val values = inNameOrder.map { record ->
                record.fileName to (record.episode ?: metadataNumbers[record.fileId])
            }
            val numbers = FilmNameParser.numberEpisodeValues(values)
            inNameOrder.forEachIndexed { index, record ->
                numbered += NumberedEpisode(
                    record = record,
                    number = numbers[index],
                    endNumber = record.endEpisode,
                    kind = record.kind,
                )
            }
        }

        return numbered.sortedWith { left, right ->
            compare(
                left.record.copy(episode = left.number),
                right.record.copy(episode = right.number),
            )
        }
    }

    fun compareSeason(left: String?, right: String?): Int {
        val leftKey = seasonRank(left)
        val rightKey = seasonRank(right)
        if (leftKey != rightKey) return leftKey.compareTo(rightKey)

        if (left == null || right == null) return 0
        val byNumber = (FilmNameParser.detectedSeasonNumber(left) ?: Int.MAX_VALUE)
            .compareTo(FilmNameParser.detectedSeasonNumber(right) ?: Int.MAX_VALUE)
        if (byNumber != 0) return byNumber
        return FilmNameParser.naturalCompare(left, right)
    }

    private fun seasonRank(season: String?): Int = when {
        season.isNullOrBlank() -> 0
        FilmNameParser.isSpecialSeason(season) -> 1
        season.equals(FilmNameParser.OTHER_SEASON, ignoreCase = true) -> 3
        FilmNameParser.detectedSeasonNumber(season) != null -> 2
        else -> 2
    }

    private fun kindRank(kind: EpisodeKind): Int = when (kind) {
        EpisodeKind.SPECIAL -> 0
        EpisodeKind.REGULAR -> 1
        EpisodeKind.DECIMAL -> 2
    }

    fun displayName(episode: NumberedEpisode, includeSeason: Boolean = false): String {
        // Release versions (`v2`, `v03`) only decide the order of alternate releases; they are
        // noise in an episode name.
        val marker = FilmNameParser.formatEpisode(episode.number, episode.endNumber, episode.kind)
        if (!includeSeason) return marker
        val season = episode.record.season?.takeIf { it.isNotBlank() } ?: return marker
        val label = FilmNameParser.cleanAnimeName(season).ifBlank { season }
        return "$label $marker"
    }
}
