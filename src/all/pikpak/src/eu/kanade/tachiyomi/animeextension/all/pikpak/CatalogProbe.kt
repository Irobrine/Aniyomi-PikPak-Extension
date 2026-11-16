package eu.kanade.tachiyomi.animeextension.all.pikpak

/**
 * Decides whether a folder should open on a seasons screen.
 *
 * A season-like sub-folder is enough, but a flat folder can also carry its seasons in file names
 * (`Show S01E01`, `Show S02E01`). Disc, quality, subtitle and generic container folders do not
 * create a seasons screen; their contents belong to the same flat episode run.
 */
internal object CatalogProbe {
    fun needsSeasonScreen(folderNames: List<String>, videoNames: List<String>): Boolean {
        val seasonLikeFolder = folderNames.any { name ->
            val role = DirectoryAnalyzer.classify(name).role
            role == DirectoryRole.SEASON || role == DirectoryRole.PART ||
                role == DirectoryRole.COUR || role == DirectoryRole.SPECIAL ||
                role == DirectoryRole.TITLE
        }
        if (seasonLikeFolder) return true

        val fileSeasons = videoNames
            .asSequence()
            .mapNotNull { FilmNameParser.seasonKeyFromFileName(it) }
            .distinct()
            .take(2)
            .count()
        return fileSeasons > 1
    }
}
