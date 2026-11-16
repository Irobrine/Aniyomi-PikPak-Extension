package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakFile
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Direct PikPak source.
 *
 * The extension imports a session from PikPak's own web login, lists "My Pack",
 * resolves season folders from the real PikPak paths, and generates signed PikPak
 * playback links itself. It does not use StremThru.
 */
@Source
abstract class PikPak :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val strings by lazy { PikPakStrings.get() }

    private val filterList = CatalogFilters.newFilterList()

    override fun getFilterList(): AnimeFilterList = filterList

    private val libraryCache = TtlCache<String, Library>(MAX_LIBRARY_ENTRIES)
    private val catalogCache = TtlCache<String, List<CatalogItem>>(MAX_CATALOG_ENTRIES)
    private val extraFolderCache = TtlCache<String, List<CatalogItem>>(MAX_EXTRA_FOLDER_ENTRIES)
    private val seasonProbeCache = TtlCache<String, Boolean>(MAX_PROBE_ENTRIES)
    private val streamCache = TtlCache<String, List<PikPakClient.StreamVariant>>(MAX_STREAM_ENTRIES)

    @Volatile
    private var directClientInstance: PikPakClient? = null

    @Volatile
    private var metadataClientKey: String? = null

    @Volatile
    private var metadataClient: MetadataClient? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        Log.d(TAG, "popular page=$page")
        val items = CatalogFilters.apply(myPack(), CatalogFilters.sortIndex(filterList), CatalogFilters.typeIndex(filterList))
        return catalogPage(items, page)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val items = CatalogFilters.apply(myPack(), CatalogFilters.SORT_NEWEST, CatalogFilters.typeIndex(filterList))
        return catalogPage(items, page)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)

        Log.d(TAG, "search page=$page query=$query")
        val matches = CatalogFilters.search(myPack(), query)
        val sorted = CatalogFilters.apply(
            matches,
            CatalogFilters.sortIndex(filters, CatalogFilters.SORT_NAME_ASC),
            CatalogFilters.typeIndex(filters),
        )
        return catalogPage(sorted, page)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        Log.d(TAG, "details start url=${anime.url}")
        val (itemId, season) = parseSeasonUrl(anime.url)
        if (season != null) {
            anime.fetch_type = FetchType.Episodes
            anime.initialized = true
            return anime
        }

        val library = libraryFor(itemId)
        if (library.name.isNotBlank()) {
            anime.title = if (library.isFolder) {
                cleanAnimeName(library.name)
            } else {
                titleFromFileName(library.name).ifBlank { cleanAnimeName(library.name) }
            }
        }
        val seasons = library.seasons.ifEmpty { orderedSeasons(library.entries) }
        anime.fetch_type = if (
            library.isFolder && (library.hasSeasonFolders || library.seasons.size > 1)
        ) {
            FetchType.Seasons
        } else {
            FetchType.Episodes
        }
        if (preferences.getBoolean(PikPakPrefs.METADATA, true)) {
            // The first regular season's cover represents the whole show.
            val metadataSeason = seasons.firstOrNull {
                !isSpecialSeason(it) && !it.equals(FilmNameParser.OTHER_SEASON, ignoreCase = true)
            } ?: seasons.firstOrNull { !it.equals(FilmNameParser.OTHER_SEASON, ignoreCase = true) }
            val seriesHint = library.entries.firstOrNull {
                metadataSeason == null || it.season == metadataSeason
            }?.seriesHint
            val metadata = fetchMetadata(library.name, metadataSeason, library.classification.kind, seriesHint)
            if (metadata != null) applyMetadata(anime, metadata)
        }
        anime.initialized = true
        Log.d(TAG, "details item=$itemId seasons=$seasons type=${anime.fetch_type} kind=${library.classification.kind}")
        return anime
    }

    // =============================== Seasons ==============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        Log.d(TAG, "season list start url=${anime.url}")
        val (itemId, season) = parseSeasonUrl(anime.url)
        if (season != null) return emptyList()

        val library = libraryFor(itemId)
        val seasons = library.seasons.ifEmpty { orderedSeasons(library.entries) }
        Log.d(TAG, "seasons item=$itemId seasons=$seasons kind=${library.classification.kind}")
        if (seasons.isEmpty()) {
            return listOf(
                SAnime.create().apply {
                    title = SYNTHETIC_SEASON_TITLE
                    url = "$itemId$SEASON_SEPARATOR$SYNTHETIC_SEASON_ID"
                    season_number = 1.0
                    fetch_type = FetchType.Episodes
                    initialized = true
                    thumbnail_url = anime.thumbnail_url
                    description = anime.description
                    genre = anime.genre
                    status = anime.status
                },
            )
        }

        val metadataEnabled = preferences.getBoolean(PikPakPrefs.METADATA, true)
        val parentTitle = if (library.isFolder) {
            cleanAnimeName(library.name)
        } else {
            titleFromFileName(library.name).ifBlank { cleanAnimeName(library.name) }
        }
        val metadataSemaphore = Semaphore(METADATA_CONCURRENCY)
        return coroutineScope {
            seasons.map { label ->
                async {
                    SAnime.create().apply {
                        title = seasonDisplayTitle(parentTitle, label)
                        url = "$itemId$SEASON_SEPARATOR$label"
                        season_number = seasonNumberFromLabel(label)
                        fetch_type = FetchType.Episodes
                        initialized = true
                        thumbnail_url = anime.thumbnail_url
                        description = anime.description
                        genre = anime.genre
                        status = anime.status
                        if (metadataEnabled) {
                            val seriesHint = library.entries.firstOrNull {
                                MediaText.normalizedKey(it.season.orEmpty()) == MediaText.normalizedKey(label)
                            }?.seriesHint
                            val metadata = metadataSemaphore.withPermit {
                                fetchMetadata(library.name, label, library.classification.kind, seriesHint)
                            }
                            if (metadata != null) applyMetadata(this, metadata)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        Log.d(TAG, "episode list start url=${anime.url}")
        val (itemId, seasonFilter) = parseSeasonUrl(anime.url)
        val library = libraryFor(itemId)

        val requestingOther = seasonFilter?.equals(FilmNameParser.OTHER_SEASON, ignoreCase = true) == true
        val useMetadataFallback = !requestingOther && (seasonFilter != null || library.seasons.size <= 1)
        val metadata = if (preferences.getBoolean(PikPakPrefs.METADATA, true) && useMetadataFallback) {
            val metadataSeason = seasonFilter?.takeUnless { it == SYNTHETIC_SEASON_ID }
            val metadataEntry = metadataSeason?.let { wanted ->
                val wantedKey = MediaText.normalizedKey(wanted)
                val wantedIdentity = FilmNameParser.seasonIdentity(wanted)
                library.entries.firstOrNull { it.season == wanted }
                    ?: library.entries.firstOrNull { entry ->
                        val label = entry.season.orEmpty()
                        MediaText.normalizedKey(label) == wantedKey ||
                            FilmNameParser.seasonIdentity(label) == wantedIdentity
                    }
            } ?: library.entries.firstOrNull()
            fetchMetadata(
                library.name,
                metadataSeason,
                library.classification.kind,
                metadataEntry?.seriesHint,
            )
        } else {
            null
        }

        val filtered = if (seasonFilter == null || seasonFilter == SYNTHETIC_SEASON_ID) {
            library.entries
        } else {
            val exact = library.entries.filter { it.season == seasonFilter }
            if (exact.isNotEmpty()) {
                exact
            } else {
                val wantedKey = MediaText.normalizedKey(seasonFilter)
                val wantedIdentity = FilmNameParser.seasonIdentity(seasonFilter)
                library.entries.filter { entry ->
                    val label = entry.season.orEmpty()
                    MediaText.normalizedKey(label) == wantedKey ||
                        FilmNameParser.seasonIdentity(label) == wantedIdentity
                }
            }
        }

        val records = filtered.map { it.toRecord() }
        val metadataNumbers = if (useMetadataFallback) {
            filtered.associate { entry ->
                entry.fileId to metadata?.resolveEpisodeValue(entry.fileName)
            }
        } else {
            emptyMap()
        }
        val numbered = EpisodeOrdering.numberBySeason(records, metadataNumbers)
        val includeSeason = library.seasons.size > 1 &&
            (seasonFilter == null || seasonFilter == SYNTHETIC_SEASON_ID)

        Log.d(
            TAG,
            "episodes item=$itemId season=$seasonFilter count=${numbered.size} kind=${library.classification.kind}",
        )
        // Aniyomi expects sources to return episodes from most to least recent and uses the
        // list position as the episode's source order, so the newest episode must come first.
        return numbered
            .asReversed()
            .map { episode ->
                SEpisode.create().apply {
                    episode_number = episode.number.toFloat()
                    name = EpisodeOrdering.displayName(episode, includeSeason)
                    url = episode.record.fileId
                    date_upload = parseDate(episode.record.released)
                    preview_url = episode.record.thumbnail.orEmpty()
                }
            }
    }

    // ============================== Hosters ===============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val fileId = episode.url
        if (fileId.startsWith("{") || fileId.contains(':')) {
            throw IOException(strings.olderEpisodeList())
        }

        // Hosters must carry their videos: host apps that ignore lazy hosters would otherwise
        // show "No available video". The file id is kept so getVideoList can refresh the links.
        val videos = resolveVideos(fileId)
        return listOf(
            Hoster(
                hosterName = "PikPak",
                videoList = videos,
                internalData = fileId,
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val fileId = hoster.internalData.takeIf { it.isNotBlank() }
            ?: return hoster.videoList.orEmpty()

        // Re-resolve so an expired link from the hoster list is never played; fall back to the
        // eager list when the refresh fails.
        return try {
            resolveVideos(fileId).ifEmpty { hoster.videoList.orEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "stream refresh failed file=$fileId error=${e.message}")
            hoster.videoList.orEmpty()
        }
    }

    private suspend fun resolveVideos(fileId: String): List<Video> {
        streamCache.get(fileId)?.let { variants ->
            val now = System.currentTimeMillis()
            val fresh = variants.filter { it.expiresAt <= 0L || it.expiresAt > now }
            if (fresh.isNotEmpty()) {
                return QualitySelector.select(fresh, preferredQuality()).map { it.toVideo() }
            }
        }

        val variants = try {
            directClient().getStreamVariants(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "stream resolve failed file=$fileId error=${e.message}")
            throw IOException(strings.streamFailed(e.message.orEmpty()), e)
        }

        if (variants.isEmpty()) {
            throw IOException(strings.noPlaybackLink())
        }

        Log.d(TAG, "stream resolved file=$fileId variants=${variants.size}")
        streamCache.put(fileId, variants, STREAM_CACHE_MS)
        return QualitySelector.select(variants, preferredQuality()).map { it.toVideo() }
    }

    private fun preferredQuality(): String = preferences.getString(PikPakPrefs.QUALITY, QualitySelector.AUTO).orEmpty()

    private fun PikPakClient.StreamVariant.toVideo(): Video = Video(
        videoUrl = url,
        videoTitle = if (title.startsWith("PikPak", ignoreCase = true)) title else "PikPak $title",
        resolution = PikPakClient.streamQualityRank(title).takeIf { it > 0 },
        preferred = isDefault || isOrigin,
    )

    // ============================== Catalog ===============================

    private suspend fun myPack(): List<CatalogItem> {
        val extras = extraFolders()
        val key = "${sessionKey()}|${extras.joinToString("|")}"
        catalogCache.get(key)?.let { return it }

        val client = directClient()

        val items = try {
            client.listMyPack()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "my pack failed: ${e.message}")
            throw IOException(strings.myPackFailed(e.message.orEmpty()), e)
        }

        val pinned = coroutineScope {
            val gate = Semaphore(EXTRA_FOLDER_CONCURRENCY)
            extras.map { input ->
                async { gate.withPermit { resolveExtraFolders(client, input) } }
            }.awaitAll()
        }.flatten()
            .distinctBy { it.id }
            .mapIndexed { index, item -> item.copy(order = index) }
        val pinnedIds = pinned.map { it.id }.toSet()
        val combined = pinned + filterCatalog(items)
            .map { it.toCatalogItem() }
            .filterNot { it.id in pinnedIds }
            .sortedWith(
                compareBy<CatalogItem> { if (it.isPinned) 0 else 1 }
                    .thenBy { it.order }
                    .thenByDescending { it.createdTime.orEmpty() }
                    .thenBy { it.name.lowercase(Locale.US) },
            )

        Log.d(TAG, "my pack items=${items.size} extras=${pinned.size}")
        catalogCache.put(key, combined, CATALOG_CACHE_MS)
        return combined
    }

    /**
     * Expands a configured extra folder into the anime folders it contains, so the
     * catalog lists those directly. A folder containing only episodes (or season/disc
     * sub-folders) is kept as one entry; loose video files next to series folders are included as
     * their own entries so movies and root-level files are not lost.
     */
    private suspend fun resolveExtraFolders(client: PikPakClient, input: String): List<CatalogItem> {
        extraFolderCache.get(input)?.let { return it }

        val items = try {
            val folder = client.resolveFolder(input)
            if (folder == null) {
                emptyList()
            } else {
                val children = client.listChildren(folder.id)
                    .filter { it.id.isNotBlank() }
                val childFolders = children.filter { it.kind == KIND_FOLDER }
                val videos = children.filter {
                    it.kind != KIND_FOLDER &&
                        (isVideoFile(it.name) || it.params?.url?.startsWith("magnet:", ignoreCase = true) == true)
                }
                val informativeFolders = childFolders.filterNot { folder ->
                    val role = DirectoryAnalyzer.classify(folder.name).role
                    role == DirectoryRole.GENERIC || role == DirectoryRole.RELEASE_GROUP || role == DirectoryRole.YEAR
                }
                val childRoles = informativeFolders.map { DirectoryAnalyzer.classify(it.name).role }
                val childFoldersAreSeasons = childRoles.isNotEmpty() && childRoles.all {
                    it == DirectoryRole.SEASON || it == DirectoryRole.PART ||
                        it == DirectoryRole.COUR || it == DirectoryRole.DISC ||
                        it == DirectoryRole.SPECIAL
                }

                when {
                    informativeFolders.isEmpty() -> listOf(folder.toCatalogItem(pinned = true))
                    childFoldersAreSeasons -> listOf(folder.toCatalogItem(pinned = true))
                    else -> informativeFolders.map { it.toCatalogItem(pinned = true) } +
                        videos.map { it.toCatalogItem(pinned = true) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "extra folder '$input' error=${e.message}")
            emptyList()
        }

        extraFolderCache.put(
            input,
            items,
            if (items.isNotEmpty()) EXTRA_FOLDER_CACHE_MS else EXTRA_FOLDER_MISS_MS,
        )
        Log.d(TAG, "extra folder '$input' anime=${items.size}")
        return items
    }

    private fun extraFolders(): List<String> {
        val raw = preferences.getString(PikPakPrefs.EXTRA_FOLDERS, "").orEmpty()
        if (preferences.getBoolean(PikPakPrefs.EXTRA_FOLDERS_LINES, false)) {
            return PikPakPrefs.parseExtraFolders(raw)
        }

        // One-time migration from the comma separated form: rewrite as one entry per line.
        val migrated = PikPakPrefs.splitLegacyExtraFolders(raw)
        preferences.edit()
            .putString(PikPakPrefs.EXTRA_FOLDERS, migrated.joinToString("\n"))
            .putBoolean(PikPakPrefs.EXTRA_FOLDERS_LINES, true)
            .apply()
        return migrated
    }

    private fun PikPakFile.toCatalogItem(pinned: Boolean = false) = CatalogItem(
        id = id,
        name = name,
        isFolder = kind == KIND_FOLDER,
        isTorrent = params?.url?.startsWith("magnet:", ignoreCase = true) == true,
        isPinned = pinned,
        createdTime = createdTime,
        thumbnail = thumbnailLink,
        isVideo = kind == KIND_FOLDER || isVideoFile(name) ||
            params?.url?.startsWith("magnet:", ignoreCase = true) == true,
    )

    private fun filterCatalog(items: List<PikPakFile>): List<PikPakFile> {
        val showTorrents = preferences.getBoolean(PikPakPrefs.TORRENTS, true)
        val showWebDownloads = preferences.getBoolean(PikPakPrefs.WEB_DOWNLOADS, true)

        return items.filter { item ->
            val isTorrent = item.params?.url?.startsWith("magnet:", ignoreCase = true) == true
            if (isTorrent) showTorrents else showWebDownloads
        }
    }

    private suspend fun catalogPage(items: List<CatalogItem>, page: Int): AnimesPage {
        val start = (page - 1) * PAGE_SIZE
        if (start >= items.size) return AnimesPage(emptyList(), false)

        val end = minOf(start + PAGE_SIZE, items.size)
        val pageItems = items.subList(start, end)

        val client = directClient()
        val probeFolders = preferences.getBoolean(PikPakPrefs.FLAT_FOLDERS, true)
        val foldersWithChildren = if (!probeFolders) {
            emptyMap()
        } else {
            val semaphore = Semaphore(FOLDER_PROBE_CONCURRENCY)
            coroutineScope {
                pageItems.filter { it.isFolder }.map { item ->
                    async {
                        item.id to semaphore.withPermit { folderHasSubfolders(client, item.id) }
                    }
                }.awaitAll().toMap()
            }
        }

        val animes = pageItems.map { item ->
            val hasSeasons = item.isFolder && foldersWithChildren[item.id] != false
            val classification = MediaClassifier.classifyCatalog(
                name = item.name,
                isFolder = item.isFolder,
                isTorrent = item.isTorrent,
                isVideo = item.isVideo,
            )
            SAnime.create().apply {
                title = if (item.isFolder) {
                    cleanAnimeName(item.name).ifBlank { item.id }
                } else {
                    titleFromFileName(item.name).ifBlank { cleanAnimeName(item.name).ifBlank { item.id } }
                }
                url = item.id
                fetch_type = if (hasSeasons) FetchType.Seasons else FetchType.Episodes
                description = when {
                    item.isPinned && item.isFolder -> strings.descriptionExtraFolder()
                    item.isTorrent -> strings.descriptionTorrent()
                    classification.kind != MediaKind.OTHER -> strings.descriptionKind(classification.kind)
                    else -> strings.descriptionFile()
                }
                thumbnail_url = item.thumbnail?.takeIf { it.isNotBlank() } ?: FALLBACK_POSTER
            }
        }

        return AnimesPage(animes, end < items.size)
    }

    /**
     * A folder without season-like sub-folders is a flat anime: show its episodes directly
     * instead of a synthetic season. Disc, release-group and generic "Videos" folders do not
     * count because their contents belong to the same flat episode run. Probe failures fall back
     * to the seasons screen.
     */
    private suspend fun folderHasSubfolders(client: PikPakClient, folderId: String): Boolean {
        seasonProbeCache.get(folderId)?.let { return it }

        val has = try {
            val children = client.listChildren(folderId)
            CatalogProbe.needsSeasonScreen(
                folderNames = children.filter { it.kind == KIND_FOLDER }.map { it.name },
                videoNames = children
                    .filter { it.kind != KIND_FOLDER && isVideoFile(it.name) }
                    .map { it.name },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "season probe failed folder=$folderId error=${e.message}")
            true
        }

        seasonProbeCache.put(folderId, has, SEASON_PROBE_CACHE_MS)
        return has
    }

    // ============================== Library ===============================

    private suspend fun libraryFor(itemId: String): Library {
        val key = "${sessionKey()}|$itemId"
        libraryCache.get(key)?.let { return it }

        val library = directLibrary(itemId)
        libraryCache.put(key, library, LIBRARY_CACHE_MS)
        return library
    }

    private suspend fun directLibrary(itemId: String): Library {
        val client = directClient()

        val listing = try {
            client.list(itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "direct failed item=$itemId error=${e.message}")
            throw IOException(strings.itemFailed(e.message.orEmpty()), e)
        }

        val entries = listing.files
            .filter { file ->
                val fileName = file.name.ifBlank { file.path.substringAfterLast('/') }
                isVideoFile(fileName) || file.isTorrent
            }
            .map { file ->
                val fileName = file.name.ifBlank { file.path.substringAfterLast('/') }
                LibraryAnalyzer.VideoFile(
                    id = file.id,
                    name = fileName,
                    path = file.path,
                    createdTime = file.createdTime,
                    thumbnail = file.thumbnailLink,
                    isVideo = isVideoFile(fileName) || file.isTorrent,
                )
            }

        val analysis = LibraryAnalyzer.analyze(
            itemName = listing.name,
            files = entries,
            isFolder = listing.rootKind == KIND_FOLDER,
        )
        val finalEntries = analysis.entries.map { entry ->
            EpisodeEntry(
                fileName = entry.fileName,
                fileId = entry.fileId,
                season = entry.season,
                episode = entry.episode,
                endEpisode = entry.endEpisode,
                kind = entry.kind,
                version = entry.version,
                released = entry.released,
                thumbnail = entry.thumbnail,
                seriesHint = entry.seriesHint,
            )
        }
        if (finalEntries.none { it.season != null }) {
            Log.d(TAG, "no seasons item=$itemId paths=${listing.files.take(8).map { it.path }}")
        }
        Log.d(
            TAG,
            "direct item=$itemId files=${listing.files.size} videos=${finalEntries.size} " +
                "seasons=${analysis.seasons} kind=${analysis.mediaKind}",
        )
        return Library(
            listing.name,
            finalEntries,
            analysis.seasons,
            analysis.classification,
            analysis.isFolder,
            analysis.hasSeasonFolders,
        )
    }

    @Synchronized
    private fun metadataClient(): MetadataClient {
        val key = preferences.getString(PikPakPrefs.TMDB_KEY, "").orEmpty().trim()
        metadataClient?.takeIf { metadataClientKey == key }?.let { return it }

        return MetadataClient(client, key).also {
            metadataClient = it
            metadataClientKey = key
        }
    }

    private suspend fun fetchMetadata(
        parentTitle: String,
        season: String?,
        kind: MediaKind = MediaKind.SERIES,
        seriesHint: String? = null,
    ): AnimeMetadata? {
        val usableSeason = season?.takeUnless { it.equals(FilmNameParser.OTHER_SEASON, ignoreCase = true) }
        val qualifiedParent = usableSeason?.let { FilmNameParser.seasonQualifier(it) }
        val effectiveParent = qualifiedParent
            ?: seriesHint?.takeIf { it.isNotBlank() }
            ?: parentTitle
        val parent = metadataSearchTitle(effectiveParent)
        if (parent.isBlank()) return null

        val sameAsParent = usableSeason != null &&
            MediaText.normalizedKey(FilmNameParser.cleanAnimeName(usableSeason)) == MediaText.normalizedKey(effectiveParent)
        val querySeason = usableSeason?.takeUnless { sameAsParent }

        val format = preferredMetadataFormat(kind)
        val queries = metadataQueries(parent, querySeason)
        val tmdbSeason = querySeason?.let {
            if (isSpecialSeason(it)) 0 else detectedSeasonNumber(it)
        }
        val year = FilmNameParser.metadataYear(effectiveParent) ?: FilmNameParser.metadataYear(parentTitle)
        queries.forEach { query ->
            val metadata = metadataClient().fetch(query, tmdbSeason, year, format)
            if (metadata != null) {
                Log.d(TAG, "metadata query=$query season=$season found=true")
                return metadata
            }
        }

        Log.d(TAG, "metadata season=$season found=false tried=$queries")
        return null
    }

    private fun metadataQueries(parent: String, season: String?): List<String> {
        val base = parent.ifBlank { return emptyList() }
        if (season.isNullOrBlank() || season == SYNTHETIC_SEASON_ID) {
            return titleVariants(base)
        }

        val queries = mutableListOf<String>()
        val number = detectedSeasonNumber(season)
        when {
            isSpecialSeason(season) -> {
                queries += "$base Specials"
                queries += "$base OVA"
                queries += "$base ONA"
                queries += "$base Movie"
            }
            number == 0 -> queries += "$base Season 0"
            number != null -> {
                queries += "$base Season $number"
                ordinalSeasonSuffix(number)?.let { queries += "$base $it" }
                EpisodeParser.partNumber(season)?.let {
                    queries += "$base Season $number Part $it"
                    queries += "$base Part $it"
                }
                EpisodeParser.courNumber(season)?.let { queries += "$base Cour $it" }
            }
            else -> {
                val label = metadataSearchTitle(season).ifBlank { cleanAnimeName(season) }
                queries += "$base $label"
                pluralVariant(label)?.let { queries += "$base $it" }
            }
        }
        // Last resort: the parent entry, which still gives a cover and description.
        queries += base
        queries += titleVariants(base)
        return queries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { MediaText.normalizedKey(it) }
            .take(MAX_METADATA_QUERIES)
    }

    /** Alternate spellings that commonly appear in multilingual folder names. */
    private fun titleVariants(base: String): List<String> {
        val variants = mutableListOf(base)
        COLON_SPLIT.find(base)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length >= 3 && it != base }
            ?.let { variants += it }
        if (base.contains('&')) variants += base.replace("&", " and ")
        if (base.contains(" and ", ignoreCase = true)) {
            variants += base.replace(Regex("(?i)\\s+and\\s+"), " & ")
        }
        SEASON_SUFFIX.find(base)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length >= 3 && it != base }
            ?.let { variants += it }
        TRAILING_EPISODE_NUMBER.find(base)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length >= 3 && it != base }
            ?.let { variants += it }
        val withoutDashes = base.replace(Regex("\\s*[-–—]\\s*"), " ")
        if (withoutDashes != base) variants += withoutDashes
        return variants
            .map { WHITESPACE_REGEX.replace(it, " ").trim(' ', '-', '_', '.', ',', ':', ';') }
            .filter { it.isNotBlank() }
            .distinctBy { MediaText.normalizedKey(it) }
    }

    private fun preferredMetadataFormat(kind: MediaKind): String? = when (kind) {
        MediaKind.MOVIE -> "MOVIE"
        MediaKind.OVA -> "OVA"
        MediaKind.ONA -> "ONA"
        MediaKind.SPECIAL -> "SPECIAL"
        MediaKind.SHORT -> "TV_SHORT"
        MediaKind.SERIES, MediaKind.SEASON, MediaKind.EPISODE -> "TV"
        MediaKind.OTHER -> null
    }

    private fun pluralVariant(label: String): String? {
        if (label.isBlank() || !label.last().isLetter()) return null
        return if (label.endsWith("s", ignoreCase = true)) {
            label.dropLast(1).takeIf { it.isNotBlank() && !it.endsWith("s", ignoreCase = true) }
        } else {
            "${label}s"
        }
    }

    private fun ordinalSeasonSuffix(number: Int): String? {
        if (number < 2) return null
        val suffix = when {
            number % 100 in 11..13 -> "th"
            number % 10 == 1 -> "st"
            number % 10 == 2 -> "nd"
            number % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$number$suffix Season"
    }

    private fun applyMetadata(anime: SAnime, metadata: AnimeMetadata) {
        metadata.coverUrl?.takeIf { it.isNotBlank() }?.let { anime.thumbnail_url = it }
        metadata.bannerUrl?.takeIf { it.isNotBlank() }?.let { anime.background_url = it }
        metadata.studios.joinToString(", ").takeIf { it.isNotBlank() }?.let { anime.author = it }
        metadata.genres.joinToString(", ").takeIf { it.isNotBlank() }?.let { anime.genre = it }
        metadata.status?.takeIf { it.isNotBlank() }?.let { anime.status = mapStatus(it) }
        buildMetadataDescription(metadata).takeIf { it.isNotBlank() }?.let { anime.description = it }
    }

    private fun buildMetadataDescription(metadata: AnimeMetadata): String {
        val parts = mutableListOf<String>()
        metadata.description?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }

        if (metadata.characters.isNotEmpty()) {
            parts += buildString {
                append("Characters:")
                metadata.characters.forEach { character ->
                    append("\n• ").append(character.name)
                    character.detail?.takeIf { it.isNotBlank() }?.let {
                        append(" (").append(it).append(")")
                    }
                }
            }
        }

        if (metadata.recommendations.isNotEmpty()) {
            parts += buildString {
                append("Recommendations:")
                metadata.recommendations.forEach { append("\n• ").append(it.title) }
            }
        }

        if (metadata.related.isNotEmpty()) {
            parts += buildString {
                append("Related:")
                metadata.related.forEach { suggestion ->
                    append("\n• ").append(suggestion.title)
                    suggestion.label?.takeIf { it.isNotBlank() }?.let {
                        append(" (").append(it).append(")")
                    }
                }
            }
        }

        return parts.joinToString("\n\n")
    }

    private fun mapStatus(status: String?): Int = when (status?.uppercase(Locale.US)) {
        "FINISHED", "ENDED" -> SAnime.COMPLETED
        "RELEASING", "RETURNING SERIES" -> SAnime.ONGOING
        "NOT_YET_RELEASED", "PLANNED", "IN PRODUCTION", "UPCOMING", "POST PRODUCTION" -> SAnime.ONGOING
        "CANCELLED", "CANCELED" -> SAnime.CANCELLED
        "HIATUS" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    @Synchronized
    private fun directClient(): PikPakClient = directClientInstance ?: PikPakClient(
        client = client,
        deviceId = deviceId(),
        strings = strings,
        tokenStore = tokenStore(),
        refreshSeed = refreshTokenSeed(),
    ).also { directClientInstance = it }

    /** Stable per-install device identity; PikPak ties its drive captchas to it. */
    @Synchronized
    private fun deviceId(): String {
        preferences.getString(PikPakPrefs.DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val id = UUID.randomUUID().toString().replace("-", "")
        preferences.edit().putString(PikPakPrefs.DEVICE_ID, id).apply()
        return id
    }

    /** Cache namespace for the logged-in account. Logging in or out clears these caches. */
    private fun sessionKey(): String = preferences.getString(PikPakPrefs.USER_ID, "").orEmpty()
        .ifBlank { "session" }

    private fun refreshTokenSeed(): String? {
        val raw = preferences.getString(PikPakPrefs.SESSION_SEED, "").orEmpty().trim()
        if (raw.isBlank()) return null

        val decoded = runCatching { TokenCipher.decrypt(raw) }.getOrDefault(raw)
        return PikPakPrefs.parseRefreshToken(PikPakPrefs.decodeWebValue(decoded))
    }

    private fun tokenStore(): PikPakClient.TokenStore = object : PikPakClient.TokenStore {
        override fun load(): PikPakClient.StoredTokens? {
            val version = preferences.getString(PikPakPrefs.TOKEN_CLIENT, "").orEmpty()
            if (version != PikPakPrefs.TOKEN_STORE_VERSION) return null

            val storedRefresh = preferences.getString(PikPakPrefs.REFRESH_TOKEN, "").orEmpty()
            if (storedRefresh.isBlank()) return null

            val refresh = runCatching { TokenCipher.decrypt(storedRefresh) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val access = preferences.getString(PikPakPrefs.ACCESS_TOKEN, "").orEmpty()
                .let { stored -> runCatching { TokenCipher.decrypt(stored) }.getOrDefault(stored) }

            return PikPakClient.StoredTokens(
                accessToken = access,
                refreshToken = refresh,
                userId = preferences.getString(PikPakPrefs.USER_ID, "").orEmpty(),
                expiresAt = preferences.getLong(PikPakPrefs.TOKEN_EXPIRES_AT, 0L),
            )
        }

        override fun save(tokens: PikPakClient.StoredTokens) {
            preferences.edit()
                .putString(PikPakPrefs.TOKEN_CLIENT, PikPakPrefs.TOKEN_STORE_VERSION)
                .putString(PikPakPrefs.ACCESS_TOKEN, TokenCipher.encrypt(tokens.accessToken))
                .putString(PikPakPrefs.REFRESH_TOKEN, TokenCipher.encrypt(tokens.refreshToken))
                .putString(PikPakPrefs.USER_ID, tokens.userId)
                .putLong(PikPakPrefs.TOKEN_EXPIRES_AT, tokens.expiresAt)
                .commit()
        }

        override fun clearSeed() {
            preferences.edit().remove(PikPakPrefs.SESSION_SEED).apply()
        }

        override fun clear() {
            preferences.edit()
                .remove(PikPakPrefs.TOKEN_CLIENT)
                .remove(PikPakPrefs.ACCESS_TOKEN)
                .remove(PikPakPrefs.REFRESH_TOKEN)
                .remove(PikPakPrefs.USER_ID)
                .remove(PikPakPrefs.TOKEN_EXPIRES_AT)
                .apply()
        }
    }

    private fun resetClients() {
        resetCatalog()
        directClientInstance = null
    }

    private fun resetCatalog() {
        catalogCache.clear()
        libraryCache.clear()
        extraFolderCache.clear()
        seasonProbeCache.clear()
        streamCache.clear()
    }

    // ============================= Utilities ==============================

    private fun detectedSeasonNumber(season: String): Int? = FilmNameParser.detectedSeasonNumber(season)

    private fun isSpecialSeason(season: String): Boolean = FilmNameParser.isSpecialSeason(season)

    private fun orderedSeasons(entries: List<EpisodeEntry>): List<String> = FilmNameParser.orderedSeasons(entries.mapNotNull { it.season })

    private fun seasonDisplayTitle(parent: String, label: String): String = FilmNameParser.seasonDisplayTitle(parent, label)

    private fun seasonNumberFromLabel(season: String): Double = FilmNameParser.seasonNumberFromLabel(season)

    private fun parseSeasonUrl(url: String): Pair<String, String?> = FilmNameParser.parseSeasonUrl(url)

    private fun parseDate(date: String?): Long = FilmNameParser.parseDate(date)

    private fun metadataSearchTitle(name: String): String = FilmNameParser.metadataSearchTitle(name)

    private fun cleanAnimeName(name: String): String = FilmNameParser.cleanAnimeName(name)

    private fun titleFromFileName(name: String): String = FilmNameParser.titleFromFileName(name)

    private fun isVideoFile(name: String): Boolean = FilmNameParser.isVideoFile(name)

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        PikPakPreferences(
            preferences = preferences,
            client = client,
            deviceId = ::deviceId,
            onSessionChanged = ::resetClients,
            onCatalogChanged = ::resetCatalog,
            checkExtraFolders = { inputs ->
                val client = directClient()
                inputs.map { input ->
                    input to runCatching { resolveExtraFolders(client, input).size }.getOrNull()
                }
            },
        ).setup(screen)
    }

    private data class Library(
        val name: String,
        val entries: List<EpisodeEntry>,
        val seasons: List<String>,
        val classification: MediaClassification,
        val isFolder: Boolean,
        val hasSeasonFolders: Boolean,
    )

    private data class EpisodeEntry(
        val fileName: String,
        val fileId: String,
        val season: String?,
        val episode: Double?,
        val endEpisode: Double? = null,
        val kind: EpisodeKind = EpisodeKind.REGULAR,
        val version: Int = 0,
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

    companion object {
        private const val TAG = "PikPak"

        private const val SEASON_SEPARATOR = FilmNameParser.SEASON_SEPARATOR
        private const val SYNTHETIC_SEASON_ID = FilmNameParser.SYNTHETIC_SEASON_ID
        private const val SYNTHETIC_SEASON_TITLE = FilmNameParser.SYNTHETIC_SEASON_TITLE
        private const val KIND_FOLDER = "drive#folder"

        private const val CATALOG_CACHE_MS = 60_000L
        private const val LIBRARY_CACHE_MS = 60_000L
        private const val EXTRA_FOLDER_CACHE_MS = 10 * 60 * 1000L
        private const val EXTRA_FOLDER_MISS_MS = 60 * 1000L
        private const val SEASON_PROBE_CACHE_MS = 24 * 60 * 60 * 1000L
        private const val FOLDER_PROBE_CONCURRENCY = 6
        private const val EXTRA_FOLDER_CONCURRENCY = 4
        private const val METADATA_CONCURRENCY = 4
        private const val PAGE_SIZE = 100
        private const val MAX_METADATA_QUERIES = 6

        private const val MAX_LIBRARY_ENTRIES = 128
        private const val MAX_CATALOG_ENTRIES = 32
        private const val MAX_EXTRA_FOLDER_ENTRIES = 64
        private const val MAX_PROBE_ENTRIES = 1024
        private const val MAX_STREAM_ENTRIES = 32
        private const val STREAM_CACHE_MS = 60_000L

        private const val FALLBACK_POSTER = "https://mypikpak.com/android-chrome-192x192.png"

        private val COLON_SPLIT = Regex("^\\s*([^:：]{2,})\\s*[:：].*$")
        private val SEASON_SUFFIX = Regex(
            "(?i)^\\s*(.+?)\\s+(?:s\\d{1,2}|season\\s*\\d{1,3}|\\d{1,2}(?:st|nd|rd|th)\\s+season|part\\s*\\d{1,2}|cour\\s*\\d{1,2})\\s*$",
        )
        private val TRAILING_EPISODE_NUMBER = Regex("^\\s*(.+?)\\s*[-–—]\\s*\\d{1,4}(?:\\.\\d{1,2})?\\s*$")
        private val WHITESPACE_REGEX = Regex("[\\s\\u00A0\\u3000]+")
    }
}
