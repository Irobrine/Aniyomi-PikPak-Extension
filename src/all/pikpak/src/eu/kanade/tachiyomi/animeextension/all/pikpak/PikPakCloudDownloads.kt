package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Submits cloud (offline) downloads to PikPak and follows their progress.
 *
 * The transfer itself runs on PikPak's servers, so leaving the settings screen never cancels it.
 * Only the submitted tasks are kept here, persisted so the settings page can show the same progress
 * after the app is reopened; progress is then read back from PikPak's task list.
 */
internal class PikPakCloudDownloads(
    private val preferences: SharedPreferences,
    private val clientProvider: () -> PikPakClient,
    private val onFilesChanged: () -> Unit,
    private val strings: PikPakStrings,
) {
    @Serializable
    data class Record(
        val taskId: String = "",
        val fileId: String = "",
        val name: String = "",
        val url: String = "",
        val destination: String = "",
        val phase: String = "",
        val progress: Int = 0,
        val message: String? = null,
        val updatedAt: Long = 0L,
    ) {
        val failed: Boolean
            get() = phase == PHASE_ERROR || phase == PHASE_CANCELED

        val finished: Boolean
            get() = failed || phase == PHASE_COMPLETE

        /** 0..100 for the progress line; a completed task always reads as full. */
        val percent: Int
            get() = when {
                phase == PHASE_COMPLETE -> 100
                else -> progress.coerceIn(0, 100)
            }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: ((List<Record>) -> Unit)? = null

    @Volatile
    private var isVisible: () -> Boolean = { false }

    private var pollJob: Job? = null

    /** Wires the settings screen that should render progress, resuming a previous run if needed. */
    fun watch(listener: (List<Record>) -> Unit, isVisible: () -> Boolean) {
        this.listener = listener
        this.isVisible = isVisible
        if (load().any { !it.finished }) startPolling()
    }

    fun destination(): String = preferences.getString(PikPakPrefs.CLOUD_DESTINATION, "").orEmpty()

    fun saveDestination(value: String) {
        preferences.edit().putString(PikPakPrefs.CLOUD_DESTINATION, value).apply()
    }

    @Synchronized
    fun load(): List<Record> {
        val raw = preferences.getString(PikPakPrefs.CLOUD_DOWNLOADS, "").orEmpty()
        if (raw.isBlank()) return emptyList()

        val parsed = runCatching { json.decodeFromString<List<Record>>(raw) }.getOrDefault(emptyList())
        val cutoff = now() - STALE_AFTER_MS
        val fresh = parsed.filter { !it.finished || it.updatedAt >= cutoff }
        if (fresh.size != parsed.size) store(fresh)
        return fresh
    }

    /**
     * Resolves [destination] once, then hands every link to PikPak one by one so each accepted
     * link shows up immediately. Failures are recorded per link instead of aborting the batch.
     */
    fun submit(links: List<String>, destination: String) {
        if (links.isEmpty()) return
        saveDestination(destination)

        scope.launch {
            val client = runCatching { clientProvider() }.getOrElse { error ->
                Log.d(TAG, "cloud client unavailable: ${error.message}")
                recordFailures(links, destination, error.message.orEmpty())
                return@launch
            }

            val folder = try {
                client.downloadFolder(destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "cloud destination failed: ${e.message}")
                recordFailures(links, destination, e.message.orEmpty())
                return@launch
            }

            Log.d(TAG, "cloud submit links=${links.size} destination=${folder.name} id=${folder.id}")
            var accepted = 0
            for (link in links) {
                val record = try {
                    val task = client.addCloudDownload(link, folder.id)
                    accepted++
                    Record(
                        taskId = task.id,
                        fileId = task.fileId.orEmpty(),
                        name = task.name.ifBlank { task.fileName.orEmpty() },
                        url = link,
                        destination = folder.name.ifBlank { destination },
                        phase = task.phase.takeIf { it.isNotBlank() } ?: PHASE_PENDING,
                        progress = task.progress.coerceIn(0, 100),
                        message = task.message?.takeIf { it.isNotBlank() },
                        updatedAt = now(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "cloud submit failed: ${e.message}")
                    Record(
                        url = link,
                        destination = destination,
                        phase = PHASE_ERROR,
                        message = e.message?.takeIf { it.isNotBlank() } ?: "unknown error",
                        updatedAt = now(),
                    )
                }

                addRecord(record)
            }

            if (accepted > 0) onFilesChanged()
            startPolling()
        }
    }

    /**
     * Edits a finished download: renames it and/or moves it to [newDestination]. Each successful
     * step is stored immediately so the row stays truthful if a later step fails.
     */
    fun edit(
        record: Record,
        newName: String,
        newDestination: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val fileId = record.fileId
        if (fileId.isBlank()) {
            onResult(false, null)
            return
        }

        val name = newName.trim()
        val destination = newDestination.trim()
        val rename = name.isNotBlank() && name != record.name
        val move = destination.isNotBlank() && destination != record.destination

        if (!rename && !move) {
            main.post { onResult(true, null) }
            return
        }

        scope.launch {
            val result = try {
                val client = clientProvider()

                if (rename) {
                    val saved = client.renameFile(fileId, name)
                    updateRecord(record) { it.copy(name = saved, updatedAt = now()) }
                }

                if (move) {
                    val target = client.resolveFolder(destination)
                        ?: throw IOException(strings.folderMissing(destination))
                    client.moveFile(fileId, target.id)
                    updateRecord(record) {
                        it.copy(destination = target.name.ifBlank { destination }, updatedAt = now())
                    }
                }

                onFilesChanged()
                true to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "cloud edit failed: ${e.message}")
                false to e.message
            }

            main.post { onResult(result.first, result.second) }
        }
    }

    private fun updateRecord(record: Record, transform: (Record) -> Record) {
        val records = load().map {
            if (it.taskId == record.taskId && it.url == record.url) transform(it) else it
        }
        store(records)
        notify(records)
    }

    private fun recordFailures(links: List<String>, destination: String, message: String) {
        links.forEach { link ->
            addRecord(
                Record(
                    url = link,
                    destination = destination,
                    phase = PHASE_ERROR,
                    message = message.takeIf { it.isNotBlank() } ?: "unknown error",
                    updatedAt = now(),
                ),
            )
        }
    }

    @Synchronized
    private fun addRecord(record: Record) {
        val records = (load() + record).takeLast(MAX_RECORDS)
        store(records)
        notify(records)
    }

    @Synchronized
    private fun startPolling() {
        // Restart so a reopened settings page is watched immediately, even if the previous
        // poller was about to stop because the page had been left.
        pollJob?.cancel()

        pollJob = scope.launch {
            var rounds = 0
            var hiddenRounds = 0

            while (isActive) {
                val records = load()
                if (records.none { !it.finished }) break

                if (isVisible()) {
                    hiddenRounds = 0
                } else if (++hiddenRounds > HIDDEN_ROUNDS) {
                    Log.d(TAG, "cloud polling paused while the settings screen is away")
                    break
                }

                val refreshed = refresh(records)
                if (refreshed != records) {
                    store(refreshed)
                    notify(refreshed)
                }

                rounds++
                delay(pollInterval(rounds))
            }
        }
    }

    /** Applies the newest task state, falling back to the file when PikPak dropped finished tasks. */
    private suspend fun refresh(records: List<Record>): List<Record> {
        val active = records.filter { !it.finished }
        if (active.none { it.taskId.isNotBlank() || it.fileId.isNotBlank() }) return records

        val client = clientProvider()
        val tasks = try {
            client.listCloudTasks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fall back to the file phase below rather than freezing the visible progress.
            Log.d(TAG, "cloud task list failed: ${e.message}")
            emptyList()
        }

        val byTask = tasks.associateBy { it.id }
        val byFile = tasks.mapNotNull { task -> task.fileId?.let { it to task } }.toMap()

        return records.map { record ->
            if (record.finished) return@map record

            val task = byTask[record.taskId] ?: byFile[record.fileId]
            if (task != null) {
                val taskId = task.id.ifBlank { record.taskId }
                val fileId = task.fileId.orEmpty().ifBlank { record.fileId }
                val name = record.name.ifBlank { task.name.ifBlank { task.fileName.orEmpty() } }
                val phase = task.phase.ifBlank { record.phase }
                val progress = task.progress.coerceIn(0, 100)
                val message = task.message?.takeIf { it.isNotBlank() }

                if (taskId == record.taskId && fileId == record.fileId && name == record.name &&
                    phase == record.phase && progress == record.progress && message == record.message
                ) {
                    record
                } else {
                    record.copy(
                        taskId = taskId,
                        fileId = fileId,
                        name = name,
                        phase = phase,
                        progress = progress,
                        message = message,
                        updatedAt = now(),
                    )
                }
            } else {
                val phase = record.fileId.takeIf { it.isNotBlank() }?.let { client.filePhase(it) }
                when {
                    phase == null -> record
                    phase == PHASE_COMPLETE -> record.copy(
                        phase = PHASE_COMPLETE,
                        progress = 100,
                        message = null,
                        updatedAt = now(),
                    )
                    phase == record.phase -> record
                    else -> record.copy(phase = phase, updatedAt = now())
                }
            }
        }
    }

    private fun notify(records: List<Record>) {
        val callback = listener ?: return
        main.post { callback(records) }
    }

    @Synchronized
    private fun store(records: List<Record>) {
        preferences.edit()
            .putString(PikPakPrefs.CLOUD_DOWNLOADS, json.encodeToString(records))
            .apply()
    }

    private fun pollInterval(rounds: Int): Long = when {
        rounds <= FAST_ROUNDS -> FAST_POLL_MS
        rounds <= MEDIUM_ROUNDS -> MEDIUM_POLL_MS
        else -> SLOW_POLL_MS
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "PikPak"

        const val PHASE_PENDING = "PHASE_TYPE_PENDING"
        const val PHASE_RUNNING = "PHASE_TYPE_RUNNING"
        const val PHASE_COMPLETE = "PHASE_TYPE_COMPLETE"
        const val PHASE_ERROR = "PHASE_TYPE_ERROR"
        const val PHASE_CANCELED = "PHASE_TYPE_CANCELED"

        /** Finished rows older than this are dropped when the settings page opens. */
        private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L
        private const val MAX_RECORDS = 50

        private const val FAST_POLL_MS = 3_000L
        private const val MEDIUM_POLL_MS = 10_000L
        private const val SLOW_POLL_MS = 30_000L
        private const val FAST_ROUNDS = 20
        private const val MEDIUM_ROUNDS = 40
        private const val HIDDEN_ROUNDS = 2
    }
}
