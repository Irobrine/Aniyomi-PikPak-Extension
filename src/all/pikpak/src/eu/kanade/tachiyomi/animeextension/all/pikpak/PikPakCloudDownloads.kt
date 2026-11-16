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
        /** Name the user asked for while the download was still running. */
        val pendingName: String? = null,
        /** Folder the user asked for while the download was still running. */
        val pendingDestination: String? = null,
        val pendingTries: Int = 0,
    ) {
        val failed: Boolean
            get() = phase == PHASE_ERROR || phase == PHASE_CANCELED

        val finished: Boolean
            get() = failed || phase == PHASE_COMPLETE

        /** True while an edit waits for the download to finish. */
        val hasPendingEdit: Boolean
            get() = !pendingName.isNullOrBlank() || !pendingDestination.isNullOrBlank()

        /** 0..100 for the progress line; a completed task always reads as full. */
        val percent: Int
            get() = when {
                phase == PHASE_COMPLETE -> 100
                else -> progress.coerceIn(0, 100)
            }
    }

    /** What happened to an edit request. */
    enum class EditOutcome {
        /** Applied to the file right away (it had already finished). */
        APPLIED,

        /** The download is still running, so the edit is applied when it finishes. */
        QUEUED,

        FAILED,
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
        // Restart on purpose: a reopened settings page must be watched immediately.
        if (load().any { !it.finished || it.hasPendingEdit }) startPolling()
    }

    /**
     * Applies queued edits and follows unfinished downloads.
     *
     * Called whenever the cloud download UI opens, so a rename asked for in an earlier session is
     * written once the download it belongs to has finished. Unlike [watch], an already running
     * poller is left alone so a burst of calls does not reset its backoff.
     */
    fun resume() {
        if (pollJob?.isActive == true) return
        if (load().any { !it.finished || it.hasPendingEdit }) startPolling()
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
     *
     * [names] holds the name typed for each link, by position; PikPak only exposes a file when
     * its task finishes, so a name is stored on the record and applied then.
     */
    fun submit(links: List<String>, destination: String, names: List<String?> = emptyList()) {
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
            for ((index, link) in links.withIndex()) {
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
                        pendingName = names.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() },
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
     * Renames the download and/or moves it to [newDestination].
     *
     * PikPak only exposes the file once its task has produced one, so an edit on a download that
     * is still running is remembered on the record and applied as soon as it finishes. Finished
     * downloads are edited right away, and each successful step is stored immediately so the row
     * stays truthful if a later step fails.
     */
    fun edit(
        record: Record,
        newName: String,
        newDestination: String,
        onResult: (EditOutcome, String?) -> Unit,
    ) {
        val name = newName.trim()
        val destination = newDestination.trim()

        if (record.failed) {
            main.post { onResult(EditOutcome.FAILED, record.message) }
            return
        }

        if (record.phase != PHASE_COMPLETE || record.fileId.isBlank()) {
            queueEdit(record, name, destination, onResult)
            return
        }

        val rename = name.isNotBlank() && name != record.name
        val move = destination.isNotBlank() && destination != record.destination

        if (!rename && !move) {
            main.post { onResult(EditOutcome.APPLIED, null) }
            return
        }

        scope.launch {
            val result = try {
                val client = clientProvider()

                if (rename) {
                    val saved = client.renameFile(record.fileId, name)
                    updateRecord(record) {
                        it.copy(name = saved, pendingName = null, pendingTries = 0, updatedAt = now())
                    }
                }

                if (move) {
                    val target = client.resolveFolder(destination)
                        ?: throw IOException(strings.folderMissing(destination))
                    client.moveFile(record.fileId, target.id)
                    updateRecord(record) {
                        it.copy(
                            destination = target.name.ifBlank { destination },
                            pendingDestination = null,
                            pendingTries = 0,
                            updatedAt = now(),
                        )
                    }
                }

                onFilesChanged()
                EditOutcome.APPLIED to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "cloud edit failed: ${e.message}")
                EditOutcome.FAILED to e.message
            }

            main.post { onResult(result.first, result.second) }
        }
    }

    /**
     * Stores an edit for a download that has not finished yet.
     *
     * The user can name the download while it runs; the name is shown on the row straight away and
     * written to the file once PikPak reports the task as complete.
     */
    private fun queueEdit(
        record: Record,
        name: String,
        destination: String,
        onResult: (EditOutcome, String?) -> Unit,
    ) {
        val currentName = record.pendingName?.takeIf { it.isNotBlank() } ?: record.name
        val currentDestination = record.pendingDestination?.takeIf { it.isNotBlank() } ?: record.destination
        val pendingName = name.takeIf { it.isNotBlank() && it != currentName }
        val pendingDestination = destination.takeIf { it.isNotBlank() && it != currentDestination }

        if (pendingName == null && pendingDestination == null) {
            main.post { onResult(EditOutcome.APPLIED, null) }
            return
        }

        updateRecord(record) {
            it.copy(
                pendingName = pendingName ?: it.pendingName,
                pendingDestination = pendingDestination ?: it.pendingDestination,
                pendingTries = 0,
                updatedAt = now(),
            )
        }
        Log.d(TAG, "cloud edit queued url=${record.url} name=${pendingName.orEmpty()}")
        startPolling()
        main.post { onResult(EditOutcome.QUEUED, null) }
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
            var pendingSince: Long? = null

            while (isActive) {
                val records = load()
                val active = records.any { !it.finished }
                val pending = records.any { it.hasPendingEdit }
                if (!active && !pending) break

                if (pending) {
                    if (pendingSince == null) pendingSince = now()
                } else {
                    pendingSince = null
                }

                if (isVisible()) {
                    hiddenRounds = 0
                } else if (!pending && ++hiddenRounds > HIDDEN_ROUNDS) {
                    Log.d(TAG, "cloud polling paused while the settings screen is away")
                    break
                }

                // A queued edit is worth waiting for, but only for a bounded time in the
                // background; it is reapplied the next time the cloud download UI opens.
                val waitingForEdit = !isVisible() && pending
                if (waitingForEdit && now() - (pendingSince ?: now()) > PENDING_WATCH_MS) {
                    Log.d(TAG, "cloud polling paused while an edit waits for the download")
                    break
                }

                val refreshed = refresh(records)
                if (refreshed != records) {
                    store(refreshed)
                    notify(refreshed)
                }

                rounds++
                val wait = if (waitingForEdit) maxOf(pollInterval(rounds), HIDDEN_POLL_MS) else pollInterval(rounds)
                delay(wait)
            }
        }
    }

    /** Applies the newest task state, then any edits queued for finished downloads. */
    private suspend fun refresh(records: List<Record>): List<Record> {
        val active = records.any { !it.finished }
        if (!active && records.none { it.hasPendingEdit }) return records

        val client = clientProvider()
        val refreshed = if (active) refreshTasks(client, records) else records
        return applyPendingEdits(client, refreshed)
    }

    /** Applies the newest task state, falling back to the file when PikPak dropped finished tasks. */
    private suspend fun refreshTasks(client: PikPakClient, records: List<Record>): List<Record> {
        val active = records.filter { !it.finished }
        if (active.none { it.taskId.isNotBlank() || it.fileId.isNotBlank() }) return records
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

    /**
     * Writes edits that were queued while a download was running.
     *
     * Called from the poller and whenever the cloud download UI opens, so an edit survives the app
     * being closed before the download finished.
     */
    private suspend fun applyPendingEdits(client: PikPakClient, records: List<Record>): List<Record> {
        if (records.none { it.finished && it.hasPendingEdit }) return records

        var changed = false
        val updated = records.map { record ->
            if (!record.finished || !record.hasPendingEdit) return@map record
            val edited = applyEdit(client, record)
            if (edited != record) changed = true
            edited
        }
        if (changed) onFilesChanged()
        return if (changed) updated else records
    }

    private suspend fun applyEdit(client: PikPakClient, record: Record): Record {
        // A failed download never produces a file, so there is nothing to rename or move.
        if (record.failed) {
            return record.copy(pendingName = null, pendingDestination = null, pendingTries = 0)
        }

        // The file id usually arrives with the completed task; wait a few polls for it instead of
        // dropping the edit.
        if (record.fileId.isBlank()) {
            Log.d(TAG, "queued edit waits for the file id url=${record.url}")
            return retryPending(record)
        }

        var updated = record
        var failed = false

        val name = record.pendingName?.takeIf { it.isNotBlank() }
        if (name != null) {
            val saved = try {
                client.renameFile(record.fileId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "queued rename failed file=${record.fileId}: ${e.message}")
                null
            }
            if (saved == null) {
                failed = true
            } else {
                Log.d(TAG, "queued rename applied file=${record.fileId} name=$saved")
                updated = updated.copy(name = saved, pendingName = null, pendingTries = 0, updatedAt = now())
            }
        }

        val destination = record.pendingDestination?.takeIf { it.isNotBlank() }
        if (!failed && destination != null) {
            try {
                val target = client.resolveFolder(destination)
                    ?: throw IOException(strings.folderMissing(destination))
                client.moveFile(record.fileId, target.id)
                Log.d(TAG, "queued move applied file=${record.fileId} folder=${target.name}")
                updated = updated.copy(
                    destination = target.name.ifBlank { destination },
                    pendingDestination = null,
                    updatedAt = now(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "queued move failed file=${record.fileId}: ${e.message}")
                failed = true
            }
        }

        if (!failed) return updated

        return retryPending(updated)
    }

    /** Counts a failed attempt and drops the edit after a few tries. */
    private fun retryPending(record: Record): Record {
        // Give up after a few tries instead of polling forever; the row stays editable, so the
        // user can retry from the dialog.
        val tries = record.pendingTries + 1
        return if (tries >= MAX_PENDING_TRIES) {
            Log.d(TAG, "dropping queued edit after $tries attempts file=${record.fileId}")
            record.copy(pendingName = null, pendingDestination = null, pendingTries = 0, updatedAt = now())
        } else {
            record.copy(pendingTries = tries, updatedAt = now())
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
        private const val HIDDEN_POLL_MS = 60_000L
        private const val FAST_ROUNDS = 20
        private const val MEDIUM_ROUNDS = 40
        private const val HIDDEN_ROUNDS = 2

        /** How long a queued edit keeps the poller alive while the UI is away. */
        private const val PENDING_WATCH_MS = 30 * 60 * 1000L
        private const val MAX_PENDING_TRIES = 3
    }
}
