package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.util.Log
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakAuthResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCaptchaMeta
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCaptchaRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCaptchaResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCloudDownloadRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCloudDownloadResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakCloudDownloadUrl
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakFile
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakFileListResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakMoveRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakMoveTarget
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakRefreshRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakRenameRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakTask
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakTaskListResponse
import eu.kanade.tachiyomi.network.await
import keiyoushi.network.get
import keiyoushi.network.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal PikPak drive client for web-session logins: token refresh and recursive file listing.
 *
 * The only credential is the refresh token imported from PikPak's web app. PikPak rotates it on
 * every exchange, so the rotated token is written back through [TokenStore].
 *
 * Drive reads are independent and run concurrently; only token and captcha state is serialised.
 */
internal class PikPakClient(
    private val client: OkHttpClient,
    private val deviceId: String,
    private val strings: PikPakStrings,
    private val tokenStore: TokenStore = NoOpTokenStore,
    private val captchaStore: CaptchaStore = NoOpCaptchaStore,
    refreshSeed: String? = null,
) {
    interface TokenStore {
        fun load(): StoredTokens?
        fun save(tokens: StoredTokens)

        /** Drops the one-time login seed once the rotating session has been persisted. */
        fun clearSeed() = Unit

        /** Drops the persisted session after PikPak definitively rejects it. */
        fun clear() = Unit
    }

    /**
     * Keeps the last captcha between app processes.
     *
     * Minting one takes over a second, and the host kills the app between visits, so a captcha
     * that is still valid is worth keeping: it turns the first catalog load into a few drive
     * reads instead of a full handshake. PikPak expires them on its own, so nothing older than
     * [Stored.expiresAt] is reused.
     */
    interface CaptchaStore {
        fun load(): Stored?
        fun save(captcha: Stored)
        fun clear() = Unit

        data class Stored(val action: String, val token: String, val expiresAt: Long)
    }

    data class StoredTokens(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val expiresAt: Long,
    )

    /** One playable representation of a file. */
    data class StreamVariant(
        val url: String,
        val title: String,
        val isOrigin: Boolean = false,
        val isDefault: Boolean = false,
        val expiresAt: Long = 0L,
    )

    private object NoOpTokenStore : TokenStore {
        override fun load(): StoredTokens? = null
        override fun save(tokens: StoredTokens) = Unit
    }

    private object NoOpCaptchaStore : CaptchaStore {
        override fun load(): CaptchaStore.Stored? = null
        override fun save(captcha: CaptchaStore.Stored) = Unit
    }

    data class Listing(
        val name: String,
        val rootId: String,
        val rootKind: String,
        val files: List<FileEntry>,
    )

    data class FileEntry(
        val id: String,
        val name: String,
        val path: String,
        val kind: String,
        val size: String? = null,
        val createdTime: String? = null,
        val thumbnailLink: String? = null,
        val isTorrent: Boolean = false,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // Fields such as grant_type have defaults; the API requires them in the body.
        encodeDefaults = true
    }

    /**
     * Client used for every PikPak call.
     *
     * The host's shared client allows only five requests per host, which serialises drive reads:
     * a catalog page with a few dozen folders would be resolved in as many waves. PikPak's drive
     * reads are independent, so this client keeps its own dispatcher and lets a page be probed in
     * one burst. Interceptors, cache and connection pool are inherited from the host client.
     */
    private val driveClient: OkHttpClient = client.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = MAX_REQUESTS
                maxRequestsPerHost = MAX_REQUESTS_PER_HOST
            },
        )
        .build()

    private val authMutex = Mutex()
    private val captchas = TtlCache<String, String>(MAX_CAPTCHA_ENTRIES)
    private val captchaLocks = ConcurrentHashMap<String, Mutex>()
    private val warmMutex = Mutex()

    @Volatile
    private var driveConnectionWarmedAt = 0L

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var refreshToken: String? = null

    @Volatile
    private var userId: String? = null

    @Volatile
    private var expiresAt: Long = 0

    private var seedInUse = false
    private var lastRefreshError: String? = null
    private var lastRefreshCode: Int = 0

    init {
        val stored = tokenStore.load()
        if (stored != null) {
            accessToken = stored.accessToken.takeIf { it.isNotBlank() && stored.expiresAt > nowSeconds() }
            refreshToken = stored.refreshToken.takeIf { it.isNotBlank() }
            userId = stored.userId.takeIf { it.isNotBlank() }
            expiresAt = if (accessToken != null) stored.expiresAt else 0
        }

        if (refreshToken == null) {
            // A refresh token imported from the web session is the only way in.
            refreshToken = refreshSeed?.takeIf { it.isNotBlank() }
            seedInUse = refreshToken != null
        }

        runCatching { captchaStore.load() }.getOrNull()?.let { captcha ->
            val remaining = captcha.expiresAt - System.currentTimeMillis()
            if (captcha.action.isNotBlank() && captcha.token.isNotBlank() && remaining > MIN_CAPTCHA_LIFETIME_MS) {
                captchas.put(captcha.action, captcha.token, remaining)
                Log.d(TAG, "captcha restored action=${captcha.action} expiresIn=${remaining / 1000}s")
            }
        }
    }

    // ============================== Sessions ==============================

    /**
     * Exchanges the current refresh token once and returns the resulting session.
     *
     * Used to verify a token imported from the web app, so a stale copy in the browser's storage
     * is rejected instead of being saved, and the rotated values are available right away.
     */
    suspend fun refreshSession(): StoredTokens? = authMutex.withLock {
        if (!refreshLocked()) return@withLock null

        val access = accessToken ?: return@withLock null
        val refresh = refreshToken ?: return@withLock null
        StoredTokens(access, refresh, userId.orEmpty(), expiresAt)
    }

    private suspend fun ensureSession(forceRefresh: Boolean = false) {
        authMutex.withLock {
            val now = nowSeconds()
            if (!forceRefresh && accessToken != null && now < expiresAt - 600) return

            if (refreshLocked()) return

            if (isDefinitiveRejection()) {
                // PikPak will reject this token forever; forget it so the UI shows a logged-out state.
                tokenStore.clear()
                accessToken = null
                refreshToken = null
                expiresAt = 0
                invalidateCaptchas()
            }

            throw sessionException()
        }
    }

    /** True when the next request will have to exchange the refresh token first. */
    private fun sessionNeedsRefresh(): Boolean = accessToken == null || nowSeconds() >= expiresAt - 600

    private fun isDefinitiveRejection(): Boolean {
        if (lastRefreshCode == 401 || lastRefreshCode == 403) return true
        val error = lastRefreshError ?: return false
        return error.contains("invalid_grant", ignoreCase = true) || error.contains("4126")
    }

    private fun sessionException(): IOException {
        val reason = lastRefreshError?.takeIf { it.isNotBlank() }
        return IOException(
            if (refreshToken == null) {
                strings.notLoggedIn(reason)
            } else {
                strings.sessionRejected(reason)
            },
        )
    }

    private fun persistTokens(): Boolean {
        val access = accessToken ?: return false
        val refresh = refreshToken ?: return false
        return runCatching {
            tokenStore.save(StoredTokens(access, refresh, userId.orEmpty(), expiresAt))
        }.isSuccess
    }

    private suspend fun refreshLocked(): Boolean {
        val token = refreshToken ?: return false

        val body = json.encodeToString(PikPakRefreshRequest(token, CLIENT_ID))
            .toRequestBody(JSON_MEDIA_TYPE)

        val url = USER_BASE.newBuilder()
            .addPathSegment("v1")
            .addPathSegment("auth")
            .addPathSegment("token")
            .build()

        val startedAt = System.currentTimeMillis()
        driveClient.post(url, clientHeaders(includeAuth = accessToken != null), body, ensureSuccess = false)
            .use { response ->
                val text = response.body.string()
                lastRefreshCode = response.code
                Log.d(AUTH_TAG, "refresh attempt code=${response.code} in=${System.currentTimeMillis() - startedAt}ms")

                if (!response.isSuccessful) {
                    lastRefreshError = runCatching {
                        val failure = json.decodeFromString<PikPakAuthResponse>(text)
                        failure.errorDescription?.takeIf { it.isNotBlank() }
                            ?: failure.error?.takeIf { it.isNotBlank() }
                    }.getOrNull() ?: "HTTP ${response.code}"
                    Log.d(AUTH_TAG, "refresh failed code=${response.code} reason=$lastRefreshError")
                    return false
                }

                val data = runCatching { json.decodeFromString<PikPakAuthResponse>(text) }.getOrNull()
                if (data == null || data.accessToken.isBlank()) {
                    lastRefreshError = "unreadable token response"
                    Log.d(AUTH_TAG, "refresh failed code=${response.code} reason=$lastRefreshError")
                    return false
                }

                accessToken = data.accessToken
                refreshToken = data.refreshToken.ifBlank { token }
                val newUserId = data.sub.ifBlank { data.userId }
                if (newUserId.isNotBlank() && newUserId != userId) {
                    userId = newUserId
                    invalidateCaptchas()
                }
                expiresAt = nowSeconds() + data.expiresIn
                lastRefreshError = null
                Log.d(AUTH_TAG, "refresh ok")

                if (persistTokens() && seedInUse) {
                    // The imported token has rotated, so the seed is already dead.
                    seedInUse = false
                    tokenStore.clearSeed()
                }
                return true
            }
    }

    // =============================== Drive ================================

    /** The drive root's `My Pack` folder, which is the default destination of downloads. */
    suspend fun myPackFolder(): PikPakFile {
        ensureSession()

        return listChildrenAll("").firstOrNull {
            it.kind == KIND_FOLDER && it.name.equals("My Pack", ignoreCase = true)
        } ?: throw IOException(strings.myPackMissing())
    }

    /**
     * Resolves a destination path such as `My Pack/Anime` for a cloud download. A blank or
     * unresolvable path falls back to My Pack so a typo never loses the submitted links.
     */
    suspend fun downloadFolder(input: String?): PikPakFile {
        ensureSession()

        val cleaned = input.orEmpty().trim().trim('/')
        if (cleaned.isNotBlank()) {
            resolveFolder(cleaned)
                ?.takeIf { it.kind == KIND_FOLDER && it.id.isNotBlank() }
                ?.let { return it }
        }
        return myPackFolder()
    }

    /** Hands [url] (magnet, direct file or share link) to PikPak's offline downloader. */
    suspend fun addCloudDownload(url: String, parentId: String): PikPakTask {
        ensureSession()

        val body = json.encodeToString(
            PikPakCloudDownloadRequest(
                url = PikPakCloudDownloadUrl(url),
                parentId = parentId,
            ),
        ).toRequestBody(JSON_MEDIA_TYPE)

        val endpoint = DRIVE_BASE.newBuilder()
            .addPathSegment("drive")
            .addPathSegment("v1")
            .addPathSegment("files")
            .build()

        drivePost(endpoint, body).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(strings.cloudDownloadFailed(response.code, text.take(200)))
            }

            val data = runCatching { json.decodeFromString<PikPakCloudDownloadResponse>(text) }.getOrNull()
            val task = data?.task
            if (task == null || (task.id.isBlank() && task.fileId.isNullOrBlank())) {
                val reason = data?.errorDescription?.takeIf { it.isNotBlank() }
                    ?: data?.error?.takeIf { it.isNotBlank() }
                    ?: "unreadable response"
                throw IOException(strings.cloudDownloadFailed(response.code, reason))
            }
            return task
        }
    }

    /** Recent offline download tasks of the account; older history is not needed for progress. */
    suspend fun listCloudTasks(): List<PikPakTask> {
        val tasks = mutableListOf<PikPakTask>()
        var pageToken = ""
        var pageCount = 0
        do {
            if (++pageCount > MAX_TASK_PAGES) break
            val data = listTasks(pageToken)
            data.error?.let { throw IOException(strings.listError("$it ${data.errorDescription.orEmpty()}")) }
            tasks += data.tasks.orEmpty()
            pageToken = data.nextPageToken.orEmpty()
        } while (pageToken.isNotEmpty())
        return tasks
    }

    /** Current phase of a file, used when a finished task is no longer listed by PikPak. */
    suspend fun filePhase(fileId: String): String? = try {
        getFile(fileId).phase
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Renames a finished download (a folder for multi-file torrents, or a single file). */
    suspend fun renameFile(fileId: String, name: String): String {
        ensureSession()

        val cleaned = name.trim()
        if (cleaned.isBlank()) throw IOException(strings.renameFailed("empty name"))

        val body = json.encodeToString(PikPakRenameRequest(cleaned)).toRequestBody(JSON_MEDIA_TYPE)
        val endpoint = DRIVE_BASE.newBuilder()
            .addPathSegment("drive")
            .addPathSegment("v1")
            .addPathSegment("files")
            .addPathSegment(fileId)
            .build()

        drivePatch(endpoint, body).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(strings.renameFailed(text.take(200)))
            }

            val file = runCatching { json.decodeFromString<PikPakFile>(text) }.getOrNull()
            val error = file?.errorDescription?.takeIf { it.isNotBlank() }
                ?: file?.error?.takeIf { it.isNotBlank() }
            if (error != null) throw IOException(strings.renameFailed(error))
            return file?.name?.takeIf { it.isNotBlank() } ?: cleaned
        }
    }

    /** Moves a finished download (a folder or a single file) into [parentId]. */
    suspend fun moveFile(fileId: String, parentId: String) {
        ensureSession()

        val body = json.encodeToString(
            PikPakMoveRequest(ids = listOf(fileId), to = PikPakMoveTarget(parentId)),
        ).toRequestBody(JSON_MEDIA_TYPE)
        val endpoint = DRIVE_BASE.newBuilder()
            .addPathSegment("drive")
            .addPathSegment("v1")
            .addPathSegment("files:batchMove")
            .build()

        drivePost(endpoint, body).use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(strings.moveFailed(text.take(200)))
            }

            val error = runCatching { json.decodeFromString<PikPakFile>(text) }.getOrNull()
                ?.let { it.errorDescription?.takeIf { d -> d.isNotBlank() } ?: it.error?.takeIf { e -> e.isNotBlank() } }
            if (error != null) throw IOException(strings.moveFailed(error))
        }
    }

    suspend fun list(itemId: String): Listing {
        ensureSession()
        val root = getFile(itemId)

        val files = if (root.kind == KIND_FOLDER) {
            listRecursive(root.id)
        } else {
            listOf(
                FileEntry(
                    id = root.id,
                    name = root.name,
                    path = "/${root.name}",
                    kind = root.kind,
                    size = root.size,
                    createdTime = root.createdTime,
                    thumbnailLink = root.thumbnailLink,
                    isTorrent = root.params?.url?.startsWith("magnet:", ignoreCase = true) == true,
                ),
            )
        }

        return Listing(root.name, root.id, root.kind, files)
    }

    suspend fun listChildren(folderId: String): List<PikPakFile> {
        ensureSession()
        return listChildrenAll(folderId)
            .filter { it.id.isNotBlank() && it.trashed != true }
            .filter { it.phase == null || it.phase == PHASE_COMPLETE }
    }

    /**
     * First page of [folderId] only.
     *
     * Used to describe a folder (does it hold season sub-folders, or several seasons of flat
     * files?) without reading every file it contains: one extra page of five hundred entries is
     * already far more than needed, and paginating a large folder would cost one request per
     * page.
     */
    suspend fun listChildrenFirstPage(folderId: String): List<PikPakFile> {
        ensureSession()
        return listChildrenAll(folderId, maxPages = 1)
            .filter { it.id.isNotBlank() && it.trashed != true }
            .filter { it.phase == null || it.phase == PHASE_COMPLETE }
    }

    /**
     * Every playable representation PikPak offers for [fileId], newest expiry filtered out and
     * sorted from best to worst quality.
     */
    suspend fun getStreamVariants(fileId: String): List<StreamVariant> {
        ensureSession()
        val file = getFile(fileId)
        val now = System.currentTimeMillis()

        val variants = file.medias.orEmpty().mapNotNull { media ->
            val link = media.link ?: return@mapNotNull null
            val url = link.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val expiresAt = FilmNameParser.parseExpiryMillis(link.expire)
            if (expiresAt != null && expiresAt <= now) return@mapNotNull null

            StreamVariant(
                url = url,
                title = media.resolutionName?.takeIf { it.isNotBlank() }
                    ?: if (media.isOrigin) "Original" else "PikPak",
                isOrigin = media.isOrigin,
                isDefault = media.isDefault,
                expiresAt = expiresAt ?: 0L,
            )
        }.distinctBy { it.url }

        if (variants.isNotEmpty()) {
            return variants.sortedWith(
                compareByDescending<StreamVariant> {
                    if (it.isOrigin) ORIGIN_QUALITY_RANK else streamQualityRank(it.title)
                }
                    .thenByDescending { it.isDefault },
            )
        }

        val fallback = file.webContentLink?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(StreamVariant(url = fallback, title = "PikPak", isOrigin = true, isDefault = true))
    }

    /**
     * Resolves a folder ID or a path such as `My Shared/Anime` to a folder.
     * Paths are matched by name, starting at the drive root (so `My Pack/...`
     * works) or at My Pack when the first segment is not a root folder.
     */
    suspend fun resolveFolder(input: String): PikPakFile? {
        ensureSession()

        val cleaned = input.trim().trim('/')
        if (cleaned.isBlank()) return null

        if (ID_HINT_REGEX.matches(cleaned)) {
            runCatching { getFile(cleaned) }
                .getOrNull()
                ?.takeIf { it.kind == KIND_FOLDER && it.id.isNotBlank() }
                ?.let { return it }
        }

        val segments = cleaned.split('/').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        val rootFolders = listChildrenAll("")
            .filter { it.kind == KIND_FOLDER && it.id.isNotBlank() }

        var folder = rootFolders.firstOrNull { it.name.equals(segments.first(), ignoreCase = true) }
        var remaining = segments
        if (folder != null) {
            remaining = segments.drop(1)
        } else {
            folder = rootFolders.firstOrNull { it.name.equals("My Pack", ignoreCase = true) } ?: return null
        }

        for (segment in remaining) {
            val parentId = folder?.id ?: return null
            folder = listChildrenAll(parentId).firstOrNull {
                it.kind == KIND_FOLDER && it.name.equals(segment, ignoreCase = true)
            } ?: return null
        }

        return folder
    }

    private suspend fun listChildrenAll(parentId: String, maxPages: Int = MAX_PAGES): List<PikPakFile> {
        val result = mutableListOf<PikPakFile>()
        var pageToken = ""
        var pageCount = 0
        do {
            if (++pageCount > maxPages) break
            val data = listFiles(parentId, pageToken)
            data.error?.let { throw IOException(strings.listError("$it ${data.errorDescription.orEmpty()}")) }
            result.addAll(data.files.orEmpty())
            pageToken = data.nextPageToken.orEmpty()
        } while (pageToken.isNotEmpty())
        if (pageToken.isNotEmpty()) {
            Log.d(TAG, "list truncated parent=$parentId pages=$pageCount")
        }
        return result.filter { it.id.isNotBlank() && it.trashed != true }
            .filter { it.phase == null || it.phase == PHASE_COMPLETE }
    }

    /**
     * Lists a folder tree with bounded concurrency and cycle protection.
     *
     * A folder that disappears or is inaccessible while the tree is being read does not abort the
     * whole library: its direct parent keeps the files it could list and the failed branch is
     * logged. The root itself still fails loudly so the user sees a real error instead of an empty
     * anime.
     */
    private suspend fun listRecursive(rootId: String): List<FileEntry> {
        val visited: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val gate = Semaphore(TREE_CONCURRENCY)
        return listFolder(rootId, "", 0, visited, gate, isRoot = true)
    }

    private suspend fun listFolder(
        folderId: String,
        parentPath: String,
        depth: Int,
        visited: MutableSet<String>,
        gate: Semaphore,
        isRoot: Boolean,
    ): List<FileEntry> = coroutineScope {
        if (depth > MAX_DEPTH || !visited.add(folderId)) return@coroutineScope emptyList()

        val direct = mutableListOf<FileEntry>()
        val subfolders = mutableListOf<Pair<String, String>>()

        var pageToken = ""
        var pageCount = 0
        var failed = false
        try {
            do {
                if (++pageCount > MAX_PAGES) break
                val data = listFiles(folderId, pageToken)
                data.error?.let { throw IOException(strings.listError("$it ${data.errorDescription.orEmpty()}")) }

                for (file in data.files.orEmpty()) {
                    if (file.id.isBlank()) continue
                    if (file.trashed == true) continue
                    if (file.phase != null && file.phase != PHASE_COMPLETE) continue

                    val path = if (parentPath.isEmpty()) "/${file.name}" else "$parentPath/${file.name}"
                    if (file.kind == KIND_FOLDER) {
                        subfolders += file.id to path
                    } else {
                        direct += FileEntry(
                            id = file.id,
                            name = file.name,
                            path = path,
                            kind = file.kind,
                            size = file.size,
                            createdTime = file.createdTime,
                            thumbnailLink = file.thumbnailLink,
                            isTorrent = file.params?.url?.startsWith("magnet:", ignoreCase = true) == true,
                        )
                    }
                }

                pageToken = data.nextPageToken.orEmpty()
            } while (pageToken.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isRoot) throw e
            failed = true
            Log.d(TAG, "partial folder folder=$folderId error=${e.message}")
        }

        if (pageCount > MAX_PAGES) {
            Log.d(TAG, "list truncated folder=$folderId pages=$pageCount")
        }

        // Keep the sub-folders discovered before a failure: a single unreadable page or folder
        // should reduce the library, not erase every sibling below it.
        // Keep the number of live coroutines bounded even for folders with thousands of children.
        val nested = mutableListOf<FileEntry>()
        subfolders.chunked(TREE_FANOUT).forEach { chunk ->
            val listed = chunk.map { (id, path) ->
                async {
                    gate.withPermit { listFolder(id, path, depth + 1, visited, gate, isRoot = false) }
                }
            }.awaitAll().flatten()
            nested += listed
        }

        if (failed) {
            Log.d(TAG, "partial folder=$folderId direct=${direct.size} nested=${nested.size}")
        }
        direct + nested
    }

    private suspend fun getFile(fileId: String): PikPakFile {
        val url = DRIVE_BASE.newBuilder()
            .addPathSegment("drive")
            .addPathSegment("v1")
            .addPathSegment("files")
            .addPathSegment(fileId)
            .build()

        driveGet(url).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(strings.fileHttp(response.code, body.take(200)))
            }
            val file = json.decodeFromString<PikPakFile>(body)
            if (file.id.isBlank()) {
                throw IOException(strings.fileFailed(file.error.orEmpty(), file.errorDescription.orEmpty()))
            }
            return file
        }
    }

    private suspend fun listFiles(parentId: String, pageToken: String): PikPakFileListResponse {
        val url = DRIVE_BASE.newBuilder()
            .addPathSegment("drive")
            .addPathSegment("v1")
            .addPathSegment("files")
            .addQueryParameter("thumbnail_size", "SIZE_MEDIUM")
            .addQueryParameter("limit", "500")
            .addQueryParameter("filters", FILTERS_JSON)
            .apply {
                if (parentId.isNotBlank()) addQueryParameter("parent_id", parentId)
                if (pageToken.isNotBlank()) addQueryParameter("page_token", pageToken)
            }
            .build()

        driveGet(url).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(strings.filesHttp(response.code, body.take(200)))
            }
            val data = json.decodeFromString<PikPakFileListResponse>(body)
            // Kept for diagnosing slow catalog loads: a folder with thousands of entries answers
            // with a much larger payload than the ten or so names the catalog needs.
            Log.d(
                TAG,
                "list parent=${parentId.ifBlank { "root" }} files=${data.files?.size ?: 0} bytes=${body.length}",
            )
            return data
        }
    }

    private suspend fun listTasks(pageToken: String): PikPakTaskListResponse {
        val url = DRIVE_BASE.newBuilder()
            .addPathSegment("drive")
            .addPathSegment("v1")
            .addPathSegment("tasks")
            .addQueryParameter("type", "offline")
            .addQueryParameter("thumbnail_size", "SIZE_SMALL")
            .addQueryParameter("limit", "100")
            .addQueryParameter("with", "reference_resource")
            .addQueryParameter("filters", TASK_FILTERS_JSON)
            .apply {
                if (pageToken.isNotBlank()) addQueryParameter("page_token", pageToken)
            }
            .build()

        driveGet(url).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(strings.filesHttp(response.code, body.take(200)))
            }
            return json.decodeFromString<PikPakTaskListResponse>(body)
        }
    }

    // ============================== Requests ==============================

    /**
     * Authenticated drive GET with one token refresh on 401/403 and exponential backoff for
     * rate limiting (429) and server errors (5xx).
     */
    private suspend fun driveGet(url: HttpUrl): Response = driveRequest("GET:${url.encodedPath}") { action ->
        driveClient.get(url, driveHeaders(action), CacheControl.FORCE_NETWORK, ensureSuccess = false)
    }

    /** Authenticated drive POST, with the same retry and refresh behaviour as [driveGet]. */
    private suspend fun drivePost(url: HttpUrl, body: RequestBody): Response = driveRequest("POST:${url.encodedPath}") { action ->
        driveClient.post(url, driveHeaders(action), body, ensureSuccess = false)
    }

    /** Authenticated drive PATCH, with the same retry and refresh behaviour as [driveGet]. */
    private suspend fun drivePatch(url: HttpUrl, body: RequestBody): Response = driveRequest("PATCH:${url.encodedPath}") { action ->
        val request = Request.Builder()
            .url(url)
            .headers(driveHeaders(action))
            .patch(body)
            .build()
        driveClient.newCall(request).await()
    }

    private suspend fun driveRequest(
        action: String,
        send: suspend (action: String) -> Response,
    ): Response {
        var refreshed = false
        var attempt = 0

        while (true) {
            coroutineScope {
                // Opening the drive connections is free only while something else is already
                // waiting: a token refresh or a captcha mint. Otherwise the request itself opens
                // them, in parallel with its siblings.
                val warm = if (sessionNeedsRefresh()) async { warmDriveConnection() } else null
                ensureSession()
                warm?.await()
                captchaFor(action)
            }

            val startedAt = System.currentTimeMillis()
            val response = send(action)
            val code = response.code
            Log.d(TAG, "drive $action code=$code in=${System.currentTimeMillis() - startedAt}ms")

            if (code == 401 || code == 403) {
                response.close()
                if (refreshed) throw IOException(strings.denied(code))

                refreshed = true
                invalidateCaptcha(action)
                ensureSession(forceRefresh = true)
                continue
            }

            if (code == 408 || code == 429 || code >= 500) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                response.close()

                attempt++
                if (attempt >= MAX_ATTEMPTS) {
                    throw IOException(strings.unavailable(code))
                }

                val waitMs = retryAfter?.times(1000)?.coerceIn(250L, MAX_RETRY_WAIT_MS)
                    ?: (RETRY_BASE_MS shl (attempt - 1)).coerceAtMost(MAX_RETRY_WAIT_MS)
                Log.d(TAG, "retrying after HTTP $code in ${waitMs}ms attempt=$attempt")
                delay(waitMs)
                continue
            }

            return response
        }
    }

    private suspend fun driveHeaders(action: String): Headers {
        val token = accessToken ?: throw sessionException()
        val captcha = captchaFor(action)

        return Headers.Builder()
            .add("Authorization", "Bearer $token")
            .add("X-Client-Id", CLIENT_ID)
            .add("X-Client-Version", CLIENT_VERSION)
            .add("X-Device-Id", deviceId)
            .add("X-Captcha-Token", captcha)
            .add("Referer", REFERER)
            .add("User-Agent", BROWSER_UA)
            .build()
    }

    private fun clientHeaders(includeAuth: Boolean = false): Headers {
        val builder = Headers.Builder()
            .add("X-Client-Id", CLIENT_ID)
            .add("X-Client-Version", CLIENT_VERSION)
            .add("X-Device-Id", deviceId)
            .add("Referer", REFERER)
            .add("User-Agent", BROWSER_UA)

        if (includeAuth) {
            accessToken?.let { builder.add("Authorization", "Bearer $it") }
        }
        return builder.build()
    }

    // ============================== Captchas ==============================

    /**
     * PikPak binds a captcha to the action it was minted for, so each request path keeps its own
     * until it expires. The cache is bounded because file paths are unique.
     *
     * Requests that need a captcha at the same time share one init: PikPak rejects a burst of
     * init calls from the same device ("too frequent"), and the catalog starts several reads at
     * once.
     */
    private suspend fun captchaFor(action: String): String {
        captchas.get(action)?.let { return it }

        val lock = captchaLocks.computeIfAbsent(action) { Mutex() }
        try {
            return lock.withLock {
                captchas.get(action) ?: coroutineScope {
                    // Opening the drive connections is hidden by the captcha round trip, so the
                    // requests that follow reuse them instead of handshaking one by one.
                    val warm = async { warmDriveConnection() }

                    val response = initCaptcha(action)
                    val ttlSeconds = response.expiresIn.takeIf { it > 0 } ?: DEFAULT_CAPTCHA_TTL_SECONDS
                    val ttlMs = (ttlSeconds * 1000 - CAPTCHA_EXPIRY_MARGIN_MS).coerceAtLeast(1_000L)
                    captchas.put(action, response.captchaToken, ttlMs)
                    runCatching {
                        captchaStore.save(
                            CaptchaStore.Stored(
                                action = action,
                                token = response.captchaToken,
                                expiresAt = System.currentTimeMillis() + ttlMs,
                            ),
                        )
                    }
                    withTimeoutOrNull(WARM_WAIT_MS) { warm.await() }
                    response.captchaToken
                }
            }
        } finally {
            captchaLocks.remove(action, lock)
        }
    }

    /**
     * Opens connections to the drive host without sending a request that matters.
     *
     * The answers are thrown away, but OkHttp keeps the TLS connections in the pool for the
     * requests that follow. PikPak's drive API answers with HTTP/1.1, so one connection can only
     * carry one request: a catalog page reads several folders at once and would otherwise pay a
     * separate handshake for each one after the captcha. Opening them here overlaps that work
     * with the captcha and with any session refresh, neither of which needs a drive connection.
     */
    private suspend fun warmDriveConnection() {
        // Idle connections are dropped after a few minutes, so a warm-up only counts for a while.
        if (System.currentTimeMillis() - driveConnectionWarmedAt < WARM_REFRESH_MS) return

        warmMutex.withLock {
            if (System.currentTimeMillis() - driveConnectionWarmedAt < WARM_REFRESH_MS) return
            val startedAt = System.currentTimeMillis()
            val warmed = coroutineScope {
                (0 until WARM_CONNECTIONS).map { async { warmOneConnection() } }.awaitAll().any { it }
            }

            if (warmed) {
                driveConnectionWarmedAt = System.currentTimeMillis()
                Log.d(
                    TAG,
                    "drive connection warm connections=$WARM_CONNECTIONS in=${System.currentTimeMillis() - startedAt}ms",
                )
            }
        }
    }

    private suspend fun warmOneConnection(): Boolean = runCatching {
        withTimeoutOrNull(WARM_CONNECT_TIMEOUT_MS) {
            val request = Request.Builder()
                .url(DRIVE_BASE)
                .head()
                .build()
            // Reading the body is what lets OkHttp keep the connection in the pool.
            driveClient.newCall(request).await().use { it.body.string() }
            true
        } ?: false
    }.getOrDefault(false)

    private fun invalidateCaptcha(action: String) {
        captchas.remove(action)
        runCatching { captchaStore.clear() }
    }

    private fun invalidateCaptchas() {
        captchas.clear()
        runCatching { captchaStore.clear() }
    }

    private suspend fun initCaptcha(action: String): PikPakCaptchaResponse {
        val timestamp = System.currentTimeMillis().toString()
        val meta = PikPakCaptchaMeta(
            captchaSign = calculateCaptchaSign(deviceId, timestamp),
            clientVersion = CLIENT_VERSION,
            packageName = PACKAGE_NAME,
            timestamp = timestamp,
            userId = userId,
        )

        val requestBody = json.encodeToString(PikPakCaptchaRequest(action, CLIENT_ID, deviceId, meta))
            .toRequestBody(JSON_MEDIA_TYPE)

        val url = USER_BASE.newBuilder()
            .addPathSegment("v1")
            .addPathSegment("shield")
            .addPathSegment("captcha")
            .addPathSegment("init")
            .build()

        val startedAt = System.currentTimeMillis()
        driveClient.post(
            url,
            clientHeaders(includeAuth = accessToken != null),
            requestBody,
            ensureSuccess = false,
        ).use { response ->
            Log.d(TAG, "captcha $action code=${response.code} in=${System.currentTimeMillis() - startedAt}ms")
            val body = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 409 || body.contains("too frequent", ignoreCase = true)) {
                    throw IOException(strings.rateLimited())
                }
                throw IOException(strings.captchaHttp(response.code, body.take(200)))
            }

            val data = json.decodeFromString<PikPakCaptchaResponse>(body)
            if (data.captchaToken.isBlank()) {
                throw IOException(
                    strings.captchaFailed(
                        data.error.orEmpty(),
                        data.errorDescription.orEmpty(),
                        data.url.orEmpty(),
                    ),
                )
            }
            return data
        }
    }

    companion object {
        // Identity of PikPak's web client, the same one a browser uses at mypikpak.com.
        private const val CLIENT_ID = "YUMx5nI8ZU8Ap8pm"
        private const val CLIENT_VERSION = "2.0.0"
        private const val PACKAGE_NAME = "mypikpak.com"
        private const val REFERER = "https://mypikpak.com/"
        private const val AUTH_TAG = "PikPakAuth"
        private const val TAG = "PikPak"

        private const val KIND_FOLDER = "drive#folder"
        private const val PHASE_COMPLETE = "PHASE_TYPE_COMPLETE"
        private const val MAX_DEPTH = 32
        private const val MAX_PAGES = 200
        private const val MAX_TASK_PAGES = 3
        private const val TREE_CONCURRENCY = 4
        private const val TREE_FANOUT = 32

        // PikPak's endpoints are independent reads, so they are not limited to the host
        // client's five requests per host.
        private const val MAX_REQUESTS = 64
        private const val MAX_REQUESTS_PER_HOST = 12

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_MS = 750L
        private const val MAX_RETRY_WAIT_MS = 10_000L
        private const val DEFAULT_CAPTCHA_TTL_SECONDS = 300L
        private const val CAPTCHA_EXPIRY_MARGIN_MS = 30_000L
        private const val MIN_CAPTCHA_LIFETIME_MS = 30_000L
        private const val WARM_CONNECT_TIMEOUT_MS = 3_000L
        private const val WARM_CONNECTIONS = 3
        private const val WARM_WAIT_MS = 500L
        private const val WARM_REFRESH_MS = 4 * 60 * 1000L
        private const val MAX_CAPTCHA_ENTRIES = 64
        private const val ORIGIN_QUALITY_RANK = 10_000

        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"

        private const val FILTERS_JSON =
            """{"trashed":{"eq":false},"phase":{"eq":"PHASE_TYPE_COMPLETE"}}"""

        private const val TASK_FILTERS_JSON =
            """{"phase":{"in":"PHASE_TYPE_PENDING,PHASE_TYPE_RUNNING,PHASE_TYPE_COMPLETE,PHASE_TYPE_ERROR"}}"""

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val ID_HINT_REGEX = Regex("^[A-Za-z0-9_-]{16,}$")
        private val USER_BASE = "https://user.mypikpak.com".toHttpUrl()
        private val DRIVE_BASE = "https://api-drive.mypikpak.com".toHttpUrl()
        private val QUALITY_REGEX = Regex("(?i)(\\d{3,4})p")

        private val MD5_SALTS = listOf(
            "C9qPpZLN8ucRTaTiUMWYS9cQvWOE",
            "+r6CQVxjzJV6LCV",
            "F",
            "pFJRC",
            "9WXYIDGrwTCz2OiVlgZa90qpECPD6olt",
            "/750aCr4lm/Sly/c",
            "RB+DT/gZCrbV",
            "",
            "CyLsf7hdkIRxRm215hl",
            "7xHvLi2tOYP0Y92b",
            "ZGTXXxu8E/MIWaEDB+Sm/",
            "1UI3",
            "E7fP5Pfijd+7K+t6Tg/NhuLq0eEUVChpJSkrKxpO",
            "ihtqpG6FMt65+Xk+tWUH2",
            "NhXXU9rg4XXdzo7u5o",
        )

        /** Sort key for a stream label such as `1080p`; unknowns rank lowest. */
        internal fun streamQualityRank(title: String): Int = QUALITY_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

        private fun calculateCaptchaSign(deviceId: String, timestamp: String): String {
            var sign = CLIENT_ID + CLIENT_VERSION + PACKAGE_NAME + deviceId + timestamp
            for (salt in MD5_SALTS) {
                sign = md5(sign + salt)
            }
            return "1.$sign"
        }

        private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
