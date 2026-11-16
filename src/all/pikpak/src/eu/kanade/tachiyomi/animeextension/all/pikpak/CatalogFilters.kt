package eu.kanade.tachiyomi.animeextension.all.pikpak

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/** One entry of the PikPak catalog, before it becomes an [SAnime]. */
internal data class CatalogItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val isTorrent: Boolean,
    val isPinned: Boolean = false,
    val createdTime: String? = null,
    val thumbnail: String? = null,
    val isVideo: Boolean = true,
    val order: Int = Int.MAX_VALUE,
)

internal class CatalogSortFilter :
    AnimeFilter.Select<String>(
        "Sort",
        arrayOf("Newest", "Oldest", "Name A-Z", "Name Z-A"),
    )

internal class CatalogTypeFilter :
    AnimeFilter.Select<String>(
        "Type",
        arrayOf("All", "Folders", "Single files"),
    )

/** Sorting and type filtering applied to the catalog before pagination. */
internal object CatalogFilters {
    const val SORT_NEWEST = 0
    const val SORT_OLDEST = 1
    const val SORT_NAME_ASC = 2
    const val SORT_NAME_DESC = 3

    const val TYPE_ALL = 0
    const val TYPE_FOLDERS = 1
    const val TYPE_FILES = 2

    fun newFilterList(): AnimeFilterList = AnimeFilterList(CatalogSortFilter(), CatalogTypeFilter())

    fun sortIndex(filters: AnimeFilterList?, fallback: Int = SORT_NEWEST): Int = filters?.filterIsInstance<CatalogSortFilter>()?.firstOrNull()?.state ?: fallback

    fun typeIndex(filters: AnimeFilterList?): Int = filters?.filterIsInstance<CatalogTypeFilter>()?.firstOrNull()?.state ?: TYPE_ALL

    fun apply(
        items: List<CatalogItem>,
        sortIndex: Int = SORT_NEWEST,
        typeIndex: Int = TYPE_ALL,
    ): List<CatalogItem> {
        val filtered = when (typeIndex) {
            TYPE_FOLDERS -> items.filter { it.isFolder }
            TYPE_FILES -> items.filter { !it.isFolder }
            else -> items
        }

        // Pinned extra folders keep their configured order and always stay on top.
        val pinnedFirst = compareBy<CatalogItem> { if (it.isPinned) 0 else 1 }.thenBy { it.order }
        val bySort = when (sortIndex) {
            SORT_OLDEST -> compareBy<CatalogItem> { it.createdTime.orEmpty() }
                .thenComparator { left, right -> nameCompare(left, right) }
            SORT_NAME_ASC -> Comparator<CatalogItem> { left, right -> nameCompare(left, right) }
            SORT_NAME_DESC -> Comparator<CatalogItem> { left, right -> nameCompare(right, left) }
            else -> compareByDescending<CatalogItem> { it.createdTime.orEmpty() }
                .thenComparator { left, right -> nameCompare(left, right) }
        }

        return filtered.sortedWith(pinnedFirst.then(bySort))
    }

    /** Catalog entries whose title matches [query], used by the browse search. */
    fun search(items: List<CatalogItem>, query: String): List<CatalogItem> {
        val needle = query.trim()
        if (needle.isBlank()) return items

        val needleKey = MediaText.normalizedKey(needle)
        val needleTokens = MediaText.tokens(needle)
        return items.filter { item ->
            val candidates = listOf(
                cleanName(item.name),
                MediaText.cleanTitle(item.name),
                MediaText.baseName(item.name),
            ).distinctBy { MediaText.normalizedKey(it) }

            candidates.any { candidate ->
                val key = MediaText.normalizedKey(candidate)
                key.contains(needleKey) ||
                    (needleTokens.isNotEmpty() && needleTokens.all { token -> key.split(' ').contains(token) }) ||
                    (MediaText.foldDiacritics(candidate).contains(needle, ignoreCase = true))
            }
        }
    }

    private fun nameCompare(left: CatalogItem, right: CatalogItem): Int = FilmNameParser.naturalCompare(cleanName(left.name), cleanName(right.name))

    private fun cleanName(name: String): String = FilmNameParser.cleanAnimeName(name)
}
