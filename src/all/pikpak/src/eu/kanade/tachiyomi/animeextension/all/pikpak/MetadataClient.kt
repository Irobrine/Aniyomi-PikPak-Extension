package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.util.Log
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListMedia
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListPageResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListVariables
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TmdbDetails
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TmdbSearchResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TmdbSearchResult
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TmdbSeasonDetails
import keiyoushi.network.get
import keiyoushi.network.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

data class AnimeMetadata(
    val title: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val studios: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val characters: List<CharacterInfo> = emptyList(),
    val recommendations: List<Suggestion> = emptyList(),
    val related: List<Suggestion> = emptyList(),
    val episodes: List<EpisodeInfo> = emptyList(),
    val episodeCount: Int? = null,
) {
    fun resolveEpisodeNumber(fileName: String): Int? = resolveEpisodeValue(fileName)?.toInt()

    /**
     * Matches a file against known episode titles and returns the episode number.
     * This is only a fallback; explicit markers in the file name are handled earlier.
     */
    fun resolveEpisodeValue(fileName: String): Double? {
        EpisodeParser.parse(fileName)?.let { return it.value }

        val fileKey = normalize(MediaText.baseName(fileName))
        if (fileKey.isBlank()) return null

        val match = episodes.asSequence()
            .mapNotNull { episode ->
                val title = normalize(episode.title)
                if (title.length < 3) return@mapNotNull null
                val matches = fileKey.contains(title) ||
                    (fileKey.length >= 3 && title.contains(fileKey))
                if (!matches) return@mapNotNull null
                episode to title.length
            }
            .maxByOrNull { it.second }
            ?: return null

        return match.first.number.toDouble()
    }

    fun hasEssentialFields(): Boolean = !coverUrl.isNullOrBlank() && !description.isNullOrBlank()

    companion object {
        fun normalize(value: String?): String = MediaText.compactKey(MediaText.baseName(value.orEmpty()))
    }
}

data class CharacterInfo(
    val name: String,
    val detail: String? = null,
)

data class Suggestion(
    val title: String,
    val label: String? = null,
)

data class EpisodeInfo(
    val number: Int,
    val title: String? = null,
    val airDate: Long? = null,
)

internal class MetadataClient(
    private val client: OkHttpClient,
    private val tmdbApiKey: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val cache = TtlCache<String, CachedMetadata>(MAX_CACHE_ENTRIES)
    private val locks = TtlCache<String, Mutex>(MAX_LOCK_ENTRIES)
    private val requestGate = Semaphore(MAX_CONCURRENT_REQUESTS)

    /** Locale used for TMDB text; follows the device so non-English libraries get localised data. */
    private val language: String = runCatching {
        Locale.getDefault().toLanguageTag()
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("und", ignoreCase = true) }
        ?: DEFAULT_LANGUAGE

    private data class CachedMetadata(val metadata: AnimeMetadata?)
    private data class RawResponse(val code: Int, val body: String, val retryAfter: String?)

    /**
     * Fetches metadata for one query. Candidates from both providers are ranked; weak matches are
     * rejected instead of being shown as if they belonged to the folder.
     */
    suspend fun fetch(
        query: String,
        seasonNumber: Int? = null,
        year: Int? = null,
        preferredFormat: String? = null,
    ): AnimeMetadata? {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return null

        val key = listOf(
            cleanQuery.lowercase(Locale.US),
            seasonNumber?.toString() ?: "-",
            year?.toString() ?: "-",
            preferredFormat?.uppercase(Locale.US).orEmpty(),
        ).joinToString("|")

        cache.get(key)?.let { return it.metadata }

        return lockFor(key).withLock {
            cache.get(key)?.let { return@withLock it.metadata }

            val aniList = fetchAniList(cleanQuery, year, seasonNumber, preferredFormat)
            val tmdb = if (
                tmdbApiKey.isNotBlank() &&
                (aniList == null || !aniList.hasEssentialFields())
            ) {
                fetchTmdb(cleanQuery, seasonNumber, year, preferredFormat)
            } else {
                null
            }

            val metadata = merge(aniList, tmdb)
            cache.put(key, CachedMetadata(metadata), if (metadata != null) CACHE_MS else MISS_CACHE_MS)
            metadata
        }
    }

    private suspend fun fetchAniList(
        query: String,
        year: Int?,
        seasonNumber: Int?,
        preferredFormat: String?,
    ): AnimeMetadata? {
        val body = json.encodeToString(AniListRequest(ANILIST_QUERY, AniListVariables(query)))
            .toRequestBody(JSON_MEDIA_TYPE)

        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add("User-Agent", "PikPak-Aniyomi-Extension")
            .build()

        val response = postWithRetry(ANILIST_URL, headers, body) ?: return null
        if (response.code !in 200..299) {
            Log.d(TAG, "anilist HTTP ${response.code} query=$query")
            return null
        }

        val data = runCatching { json.decodeFromString<AniListPageResponse>(response.body) }.getOrNull()
        val pageMedia = data?.data?.Page?.media
        val media = if (!pageMedia.isNullOrEmpty()) {
            pageMedia
        } else {
            runCatching { json.decodeFromString<AniListResponse>(response.body) }
                .getOrNull()?.data?.Media
                ?.let { listOf(it) }
                .orEmpty()
        }
        if (media.isEmpty()) {
            Log.d(TAG, "anilist no result query=$query")
            return null
        }

        val candidates = media.mapNotNull { item ->
            val id = item.id.takeIf { it > 0 } ?: return@mapNotNull null
            val titles = listOfNotNull(
                item.title?.romaji,
                item.title?.english,
                item.title?.native,
            ) + item.synonyms.orEmpty()
            val distinctTitles = titles.distinct()
            val seriesFormat = item.format.equals("TV", ignoreCase = true) ||
                item.format.equals("TV_SHORT", ignoreCase = true)
            MetadataCandidate(
                key = "anilist:$id",
                titles = distinctTitles,
                year = item.seasonYear ?: item.startDate?.year,
                format = item.format,
                isAdult = item.isAdult == true,
                popularity = item.popularity?.toDouble() ?: 0.0,
                seasonNumber = if (seriesFormat) MatchScoring.titleSeason(distinctTitles) ?: 1 else null,
                value = item,
            )
        }

        val best = MatchScoring.best(
            query = query,
            year = year,
            preferredFormat = preferredFormat,
            seasonNumber = seasonNumber,
            candidates = candidates,
        )
        if (best == null) {
            Log.d(TAG, "anilist rejected query=$query candidates=${candidates.size}")
            return null
        }

        Log.d(
            TAG,
            "anilist matched query=$query id=${best.candidate.key} " +
                "score=${"%.2f".format(Locale.US, best.score)} reasons=${best.reasons}",
        )
        return best.candidate.value.toMetadata()
    }

    private suspend fun fetchTmdb(
        query: String,
        seasonNumber: Int?,
        year: Int?,
        preferredFormat: String?,
    ): AnimeMetadata? {
        if (tmdbApiKey.isBlank()) return null

        val search = tmdbGet<TmdbSearchResponse>(
            "search/multi",
            mapOf(
                "query" to query,
                "language" to language,
                "include_adult" to "false",
            ),
        ) ?: return null

        val candidates = search.results.orEmpty().mapNotNull { result ->
            val type = result.mediaType?.lowercase(Locale.US)
            if (type != "movie" && type != "tv") return@mapNotNull null
            val titles = listOfNotNull(result.title, result.name, result.originalTitle, result.originalName)
            MetadataCandidate(
                key = "tmdb:$type:${result.id}",
                titles = titles.distinct(),
                year = parseYear(result.releaseDate ?: result.firstAirDate),
                format = if (type == "movie") "MOVIE" else "TV",
                isAdult = result.adult == true,
                popularity = result.popularity ?: 0.0,
                value = result,
            )
        }

        val best = MatchScoring.best(
            query = query,
            year = year,
            preferredFormat = preferredFormat,
            seasonNumber = seasonNumber,
            candidates = candidates,
        )
        if (best == null) {
            Log.d(TAG, "tmdb rejected query=$query candidates=${candidates.size}")
            return null
        }

        val result = best.candidate.value
        val type = result.mediaType?.lowercase(Locale.US) ?: return null
        val details = tmdbGet<TmdbDetails>(
            "$type/${result.id}",
            mapOf(
                "language" to language,
                "append_to_response" to "credits,recommendations,similar",
            ),
        ) ?: return null

        val effectiveSeason = when {
            type != "tv" -> null
            seasonNumber != null -> seasonNumber
            else -> 1
        }
        val seasonEpisodes = if (effectiveSeason != null) {
            tmdbGet<TmdbSeasonDetails>(
                "tv/${result.id}/season/$effectiveSeason",
                mapOf("language" to language),
            )?.episodes.orEmpty().mapNotNull { episode ->
                val number = episode.episodeNumber ?: return@mapNotNull null
                EpisodeInfo(number, episode.name, parseDate(episode.airDate))
            }
        } else {
            emptyList()
        }

        Log.d(
            TAG,
            "tmdb matched query=$query type=$type id=${result.id} " +
                "score=${"%.2f".format(Locale.US, best.score)} reasons=${best.reasons}",
        )
        return details.toMetadata(type, seasonEpisodes, result)
    }

    private suspend inline fun <reified T> tmdbGet(path: String, params: Map<String, String>): T? {
        val builder = TMDB_BASE.newBuilder().addPathSegments(path)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }

        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .apply {
                if (tmdbApiKey.startsWith("eyJ")) {
                    add("Authorization", "Bearer $tmdbApiKey")
                } else {
                    builder.addQueryParameter("api_key", tmdbApiKey)
                }
            }
            .build()

        val response = getWithRetry(builder.build(), headers) ?: return null
        if (response.code !in 200..299) {
            Log.d(TAG, "tmdb HTTP ${response.code} path=$path")
            return null
        }
        return runCatching { json.decodeFromString<T>(response.body) }.getOrNull()
    }

    private suspend fun postWithRetry(url: HttpUrl, headers: Headers, body: okhttp3.RequestBody): RawResponse? {
        var attempt = 0
        while (true) {
            val response = try {
                requestGate.withPermit {
                    client.post(url, headers, body, ensureSuccess = false).use { raw ->
                        RawResponse(raw.code, raw.body.string(), raw.header("Retry-After"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_HTTP_ATTEMPTS) {
                    Log.d(TAG, "post failed url=$url error=${e.message}")
                    return null
                }
                delay((RETRY_BASE_MS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MS))
                continue
            }

            if (response.code == 408 || response.code == 429 || response.code >= 500) {
                attempt++
                if (attempt >= MAX_HTTP_ATTEMPTS) return response
                delay(retryDelayMs(response, attempt))
                continue
            }
            return response
        }
    }

    private suspend fun getWithRetry(url: HttpUrl, headers: Headers): RawResponse? {
        var attempt = 0
        while (true) {
            val response = try {
                requestGate.withPermit {
                    client.get(url, headers, ensureSuccess = false).use { raw ->
                        RawResponse(raw.code, raw.body.string(), raw.header("Retry-After"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_HTTP_ATTEMPTS) {
                    Log.d(TAG, "get failed url=$url error=${e.message}")
                    return null
                }
                delay((RETRY_BASE_MS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MS))
                continue
            }

            if (response.code == 408 || response.code == 429 || response.code >= 500) {
                attempt++
                if (attempt >= MAX_HTTP_ATTEMPTS) return response
                delay(retryDelayMs(response, attempt))
                continue
            }
            return response
        }
    }

    private fun retryDelayMs(response: RawResponse, attempt: Int): Long {
        val fromHeader = response.retryAfter?.toLongOrNull()?.times(1000L)
        return fromHeader?.coerceIn(250L, MAX_RETRY_DELAY_MS)
            ?: (RETRY_BASE_MS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    @Synchronized
    private fun lockFor(key: String): Mutex {
        locks.get(key)?.let { return it }
        val mutex = Mutex()
        locks.put(key, mutex, LOCK_MS)
        return mutex
    }

    private fun merge(primary: AnimeMetadata?, fallback: AnimeMetadata?): AnimeMetadata? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return primary.copy(
            title = primary.title ?: fallback.title,
            description = primary.description ?: fallback.description,
            coverUrl = primary.coverUrl ?: fallback.coverUrl,
            bannerUrl = primary.bannerUrl ?: fallback.bannerUrl,
            studios = primary.studios.ifEmpty { fallback.studios },
            genres = primary.genres.ifEmpty { fallback.genres },
            status = primary.status ?: fallback.status,
            characters = primary.characters.ifEmpty { fallback.characters },
            recommendations = primary.recommendations.ifEmpty { fallback.recommendations },
            related = primary.related.ifEmpty { fallback.related },
            episodes = primary.episodes.ifEmpty { fallback.episodes },
            episodeCount = primary.episodeCount ?: fallback.episodeCount,
        )
    }

    private fun AniListMedia.toMetadata(): AnimeMetadata {
        val allStudios = studios?.edges.orEmpty()
        val mainStudios = allStudios.filter { it.isMain }.mapNotNull { it.node?.name }
        val studioNames = mainStudios.ifEmpty { allStudios.mapNotNull { it.node?.name } }.distinct()

        val characterList = characters?.edges.orEmpty()
            .filterNot { it.role.equals("BACKGROUND", ignoreCase = true) }
            .mapNotNull { edge ->
                val name = edge.node?.name?.full ?: return@mapNotNull null
                CharacterInfo(name, edge.role?.toRoleLabel())
            }

        val recommendationList = recommendations?.nodes.orEmpty().mapNotNull { node ->
            val media = node.mediaRecommendation ?: return@mapNotNull null
            val title = media.title?.romaji ?: media.title?.english ?: return@mapNotNull null
            Suggestion(title, "Recommended")
        }.distinctBy { it.title.lowercase(Locale.US) }

        val relatedList = relations?.edges.orEmpty().mapNotNull { edge ->
            val media = edge.node ?: return@mapNotNull null
            val title = media.title?.romaji ?: media.title?.english ?: return@mapNotNull null
            Suggestion(title, edge.relationType?.toRelationLabel())
        }.distinctBy { it.title.lowercase(Locale.US) }

        val airDates = airingSchedule?.nodes.orEmpty()
            .mapNotNull { node ->
                val episode = node.episode ?: return@mapNotNull null
                val airingAt = node.airingAt ?: return@mapNotNull null
                episode to airingAt * 1000
            }
            .toMap()

        val episodeMap = linkedMapOf<Int, EpisodeInfo>()
        airDates.forEach { (number, airDate) ->
            episodeMap[number] = EpisodeInfo(number, null, airDate)
        }
        streamingEpisodes.orEmpty().forEach { episode ->
            val number = parseEpisodeNumber(episode.title) ?: return@forEach
            val existing = episodeMap[number]
            episodeMap[number] = EpisodeInfo(number, episode.title, existing?.airDate)
        }

        return AnimeMetadata(
            title = title?.romaji ?: title?.english ?: title?.native,
            description = description?.let { Jsoup.parse(it).wholeText().trim() },
            coverUrl = coverImage?.extraLarge ?: coverImage?.large,
            bannerUrl = bannerImage,
            studios = studioNames,
            genres = genres.orEmpty(),
            status = status,
            characters = characterList,
            recommendations = recommendationList,
            related = relatedList,
            episodes = episodeMap.values.toList(),
            episodeCount = episodes ?: episodeMap.size.takeIf { it > 0 },
        )
    }

    private fun TmdbDetails.toMetadata(
        type: String,
        seasonEpisodes: List<EpisodeInfo>,
        searchResult: TmdbSearchResult?,
    ): AnimeMetadata {
        val studioNames = productionCompanies?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { networks?.mapNotNull { it.name }.orEmpty() }
            .distinct()

        val characterList = credits?.cast.orEmpty().take(8).mapNotNull { cast ->
            val name = cast.character ?: cast.name ?: return@mapNotNull null
            val detail = cast.name?.takeIf { it.isNotBlank() && it != name }?.let { "Voice: $it" }
            CharacterInfo(name, detail)
        }

        val recommendations = (recommendations?.results.orEmpty() + similar?.results.orEmpty())
            .mapNotNull { result ->
                val title = result.title ?: result.name ?: return@mapNotNull null
                Suggestion(title, "Recommended")
            }
            .distinctBy { it.title.lowercase(Locale.US) }

        val relatedList = if (type == "tv") {
            seasons.orEmpty().mapNotNull { season ->
                val number = season.seasonNumber ?: return@mapNotNull null
                val name = season.name?.takeIf { it.isNotBlank() } ?: "Season $number"
                Suggestion(name, "Season")
            }
        } else {
            listOfNotNull(belongsToCollection?.name?.let { Suggestion(it, "Collection") })
        }

        return AnimeMetadata(
            title = title ?: name ?: originalTitle ?: originalName ?: searchResult?.title ?: searchResult?.name,
            description = overview ?: searchResult?.overview,
            coverUrl = tmdbImage(posterPath ?: searchResult?.posterPath, "w500"),
            bannerUrl = tmdbImage(backdropPath ?: searchResult?.backdropPath, "w1280"),
            studios = studioNames,
            genres = genres?.mapNotNull { it.name }.orEmpty(),
            status = status,
            characters = characterList,
            recommendations = recommendations,
            related = relatedList,
            episodes = seasonEpisodes,
            episodeCount = numberOfEpisodes ?: seasonEpisodes.size.takeIf { it > 0 },
        )
    }

    private fun tmdbImage(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private fun parseYear(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2199 }

    private fun parseDate(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.time
        }.getOrNull()
    }

    private fun parseEpisodeNumber(title: String?): Int? {
        if (title.isNullOrBlank()) return null
        EpisodeParser.parse(title)?.takeIf { it.source.startsWith("explicit") }?.let { return it.number }
        return LEADING_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String.toRoleLabel(): String = when (uppercase(Locale.US)) {
        "MAIN" -> "Main"
        "SUPPORTING" -> "Supporting"
        "BACKGROUND" -> "Background"
        else -> lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
    }

    private fun String.toRelationLabel(): String = lowercase(Locale.US)
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase(Locale.US) } }

    companion object {
        private const val TAG = "PikPakMetadata"
        private const val CACHE_MS = 6 * 60 * 60 * 1000L
        private const val MISS_CACHE_MS = 30 * 60 * 1000L
        private const val LOCK_MS = 60 * 1000L
        private const val MAX_CACHE_ENTRIES = 256
        private const val MAX_LOCK_ENTRIES = 256
        private const val MAX_CONCURRENT_REQUESTS = 4
        private const val MAX_HTTP_ATTEMPTS = 3
        private const val RETRY_BASE_MS = 500L
        private const val MAX_RETRY_DELAY_MS = 5_000L
        private const val DEFAULT_LANGUAGE = "en-US"

        private val LEADING_NUMBER_REGEX = Regex("^(\\d{1,4})(?:[\\s.:-]|$)")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val ANILIST_URL = "https://graphql.anilist.co".toHttpUrl()
        private val TMDB_BASE = "https://api.themoviedb.org/3".toHttpUrl()

        private val MEDIA_FIELDS = """
            id
            title { romaji english native }
            synonyms
            description(asHtml: false)
            coverImage { extraLarge large }
            bannerImage
            genres
            status
            season
            seasonYear
            startDate { year month day }
            episodes
            duration
            format
            popularity
            averageScore
            isAdult
            studios { edges { isMain node { name } } }
            characters(perPage: 12, sort: [ROLE, RELEVANCE]) {
                edges { role node { name { full } image { large } } }
            }
            recommendations(perPage: 8) {
                nodes { mediaRecommendation { id title { romaji english } coverImage { large } } }
            }
            relations {
                edges { relationType node { id title { romaji english } coverImage { large } } }
            }
            airingSchedule(perPage: 50) { nodes { episode airingAt } }
            streamingEpisodes { title thumbnail url site }
        """.trimIndent()

        private val ANILIST_QUERY = """
            query (${'$'}search: String) {
                Page(perPage: 10) {
                    media(search: ${'$'}search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) {
                        $MEDIA_FIELDS
                    }
                }
            }
        """.trimIndent()
    }
}
