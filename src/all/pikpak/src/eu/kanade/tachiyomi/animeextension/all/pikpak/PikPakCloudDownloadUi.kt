package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import keiyoushi.utils.applicationContext
import java.lang.ref.WeakReference

/**
 * Finds the host's foreground Activity so source code can show the cloud download dialogs outside
 * the settings screen (the synthetic catalog entry and its episodes).
 *
 * Extensions cannot ask the host for a Context, so lifecycle callbacks track it; the ActivityThread
 * fallback covers the case where the extension was loaded after the screen had already resumed.
 */
internal object PikPakHostActivity {
    private var resumed: WeakReference<Activity>? = null
    private var registered = false

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    @Synchronized
    fun register() {
        if (registered) return
        registered = true
        runCatching { applicationContext.registerActivityLifecycleCallbacks(callbacks) }
            .onFailure { Log.d(TAG, "activity tracking failed: ${it.message}") }
    }

    /** Foreground Activity, preferring the one that currently has window focus. */
    fun find(): Activity? {
        register()
        val scanned = scanAll()
        scanned.firstOrNull { it.hasWindowFocus() }?.let {
            Log.d(TAG, "cloud ui activity from ActivityThread (focused)")
            return it
        }
        resumed?.get()?.takeIf { it.isAlive() && it.hasWindowFocus() }?.let {
            Log.d(TAG, "cloud ui activity from lifecycle (focused)")
            return it
        }
        resumed?.get()?.takeIf { it.isAlive() }?.let {
            Log.d(TAG, "cloud ui activity from lifecycle")
            return it
        }
        return scanned.firstOrNull().also {
            if (it == null) Log.d(TAG, "cloud ui activity not found")
        }
    }

    private fun Activity.isAlive(): Boolean = !isFinishing && !isDestroyed

    @Suppress("PrivateAPI", "DiscouragedPrivateApi")
    private fun scanAll(): List<Activity> = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null) ?: return emptyList()
        val activities = threadClass.getDeclaredField("mActivities")
            .apply { isAccessible = true }
            .get(thread) as? Map<*, *> ?: return emptyList()

        activities.values.mapNotNull { record ->
            val recordClass = record?.javaClass ?: return@mapNotNull null
            val paused = recordClass.getDeclaredField("paused")
                .apply { isAccessible = true }
                .getBoolean(record)
            val stopped = runCatching {
                recordClass.getDeclaredField("stopped")
                    .apply { isAccessible = true }
                    .getBoolean(record)
            }.getOrDefault(false)
            if (paused || stopped) return@mapNotNull null

            val activity = recordClass.getDeclaredField("activity")
                .apply { isAccessible = true }
                .get(record) as? Activity ?: return@mapNotNull null
            activity.takeIf { it.isAlive() }
        }
    }.getOrDefault(emptyList())

    private const val TAG = "PikPak"
}

/**
 * Builds the cloud download dialogs so both the settings screen and the synthetic catalog entry
 * open the same UI.
 */
internal class PikPakCloudDownloadUi(
    private val downloads: PikPakCloudDownloads,
    private val strings: PikPakStrings,
) {
    private val handler = Handler(Looper.getMainLooper())

    private var showingDialog: WeakReference<AlertDialog>? = null

    private var retries = 0

    /** Opens the add dialog, waiting briefly for a focused host Activity if necessary. */
    fun requestShow() {
        retries = 0
        // Opening the entry is the natural moment to finish edits queued in an earlier session.
        downloads.resume()
        handler.post { tryShow(closeEntryOnDismiss = true) }
    }

    /** Opens the edit dialog for a download, resolving the host Activity first. */
    fun requestEdit(record: PikPakCloudDownloads.Record) {
        handler.post { tryEdit(record) }
    }

    private fun tryEdit(record: PikPakCloudDownloads.Record) {
        val activity = PikPakHostActivity.find()
        when {
            activity != null -> showEdit(activity, record)
            retries < MAX_ACTIVITY_RETRIES -> {
                retries++
                handler.postDelayed({ tryEdit(record) }, ACTIVITY_RETRY_MS)
            }
            else -> {
                retries = 0
                toast(strings.cloudOpenSettingsHint())
            }
        }
    }

    private fun tryShow(closeEntryOnDismiss: Boolean) {
        val activity = PikPakHostActivity.find()
        when {
            activity != null -> show(activity, closeEntryOnDismiss)
            retries < MAX_ACTIVITY_RETRIES -> {
                retries++
                handler.postDelayed({ tryShow(closeEntryOnDismiss) }, ACTIVITY_RETRY_MS)
            }
            else -> {
                retries = 0
                toast(strings.cloudOpenSettingsHint())
            }
        }
    }

    /** Cloud downloads dialog: add links and tap an existing entry to rename/move it. */
    fun show(context: Context, closeEntryOnDismiss: Boolean = false) {
        val activity = context.findActivity() ?: return
        if (isDialogShowing()) return

        // Tapping a row closes this dialog and opens the editor; the entry must stay open then.
        var openingEditor = false

        val padding = (DIALOG_PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val linksInput = EditText(activity).apply {
            hint = strings.cloudLinksHint()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = LINKS_MIN_LINES
            maxLines = LINKS_MAX_LINES
        }
        val nameInput = EditText(activity).apply {
            hint = strings.cloudNamesHint()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = LINKS_MIN_LINES
            maxLines = LINKS_MAX_LINES
        }
        val destinationInput = EditText(activity).apply {
            hint = strings.cloudDestinationHint()
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(downloads.destination().ifBlank { strings.cloudDestinationHint() })
            setSelection(text.length)
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(fieldLabel(activity, strings.cloudLinksLabel()))
            addView(linksInput)
            addView(
                fieldLabel(activity, strings.cloudNamesLabel()).apply {
                    setPadding(0, padding / 2, 0, 0)
                },
            )
            addView(nameInput)
            addView(
                fieldLabel(activity, strings.cloudDestinationLabel()).apply {
                    setPadding(0, padding / 2, 0, 0)
                },
            )
            addView(destinationInput)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(strings.cloudDialogTitle())
            .setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(strings.cloudCancelAction(), null)
            .setPositiveButton(strings.cloudDownloadAction(), null)
            .create()

        addDownloadRows(layout, activity, dialog, padding, closeEntryOnDismiss) { openingEditor = true }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entries = PikPakPrefs.parseCloudEntries(
                    links = linksInput.text.toString(),
                    names = nameInput.text.toString(),
                )
                if (entries.isEmpty()) {
                    toast(strings.cloudLinksEmpty())
                    return@setOnClickListener
                }

                val destination = destinationInput.text.toString().trim()
                    .ifBlank { strings.cloudDestinationHint() }
                downloads.submit(
                    links = entries.map { it.first },
                    destination = destination,
                    names = entries.map { it.second },
                )
                toast(strings.cloudQueued(entries.size))
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            showingDialog = null
            if (closeEntryOnDismiss && !openingEditor) closeEntry(activity)
        }
        showingDialog = WeakReference(dialog)
        dialog.show()
    }

    /**
     * Leaves the synthetic catalog entry the dialog was opened from, so closing the dialog does
     * not strand the user on an anime screen that only exists to host it.
     */
    @Suppress("DEPRECATION")
    private fun closeEntry(activity: Activity) {
        handler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            try {
                // The host's ComponentActivity routes this through its back dispatcher.
                activity.onBackPressed()
            } catch (e: Exception) {
                Log.d(TAG, "cloud entry close failed: ${e.message}")
            }
        }
    }

    /** Existing downloads, one line per entry; tapping one opens the edit dialog. */
    private fun addDownloadRows(
        layout: LinearLayout,
        activity: Activity,
        dialog: AlertDialog,
        padding: Int,
        closeEntryOnDismiss: Boolean,
        onOpenEditor: () -> Unit,
    ) {
        val records = downloads.load().asReversed()
        layout.addView(
            fieldLabel(activity, strings.cloudEntryDownloadsHeader()).apply {
                setPadding(0, padding / 2, 0, 0)
            },
        )

        if (records.isEmpty()) {
            layout.addView(
                TextView(activity).apply {
                    text = strings.cloudEntryNoDownloads()
                    textSize = SMALL_TEXT_SIZE_SP
                },
            )
            return
        }

        layout.addView(
            TextView(activity).apply {
                text = strings.cloudEntryTapHint()
                textSize = SMALL_TEXT_SIZE_SP
            },
        )

        records.forEach { record ->
            val editable = !record.failed
            layout.addView(
                TextView(activity).apply {
                    val destination = record.destination.ifBlank { strings.cloudDestinationHint() }
                    text = "${record.displayTitle(strings)} — ${record.statusLabel(strings)} · $destination"
                    textSize = ROW_TEXT_SIZE_SP
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, padding / 6, 0, padding / 6)
                    if (editable) {
                        isClickable = true
                        setOnClickListener {
                            onOpenEditor()
                            dialog.dismiss()
                            this@PikPakCloudDownloadUi.handler.post {
                                showEdit(activity, record, closeEntryOnDismiss)
                            }
                        }
                    }
                },
            )
        }
    }

    /** Edit Download dialog: rename the download and/or move it to another folder. */
    fun showEdit(
        context: Context,
        record: PikPakCloudDownloads.Record,
        closeEntryOnDismiss: Boolean = false,
    ) {
        val activity = context.findActivity() ?: return
        if (isDialogShowing()) return

        val unfinished = record.phase != PikPakCloudDownloads.PHASE_COMPLETE
        val padding = (DIALOG_PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val nameInput = EditText(activity).apply {
            hint = strings.cloudRenameLabel()
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(record.pendingName?.takeIf { it.isNotBlank() } ?: record.name.ifBlank { PikPakPrefs.cloudLinkLabel(record.url).orEmpty() })
            setSelection(0, text.length)
        }
        val folderInput = EditText(activity).apply {
            hint = strings.cloudDestinationHint()
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(record.destination)
            setSelection(text.length)
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(fieldLabel(activity, strings.cloudRenameLabel()))
            addView(nameInput)
            addView(
                fieldLabel(activity, strings.cloudEditFolderLabel()).apply {
                    setPadding(0, padding / 2, 0, 0)
                },
            )
            addView(folderInput)
            if (unfinished) {
                addView(
                    TextView(activity).apply {
                        text = strings.cloudEditPendingNote()
                        textSize = SMALL_TEXT_SIZE_SP
                        setPadding(0, padding / 2, 0, 0)
                    },
                )
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(strings.cloudEditTitle())
            .setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(strings.cloudCancelAction(), null)
            .setPositiveButton(strings.cloudEditAction(), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    toast(strings.cloudRenameEmpty())
                    return@setOnClickListener
                }

                val folder = folderInput.text.toString().trim()
                dialog.dismiss()
                downloads.edit(record, name, folder) { outcome, error ->
                    val message = when (outcome) {
                        PikPakCloudDownloads.EditOutcome.APPLIED -> strings.cloudEdited()
                        PikPakCloudDownloads.EditOutcome.QUEUED -> strings.cloudEditQueued()
                        PikPakCloudDownloads.EditOutcome.FAILED ->
                            error?.takeIf { it.isNotBlank() } ?: strings.cloudEditFailed()
                    }
                    toast(message)
                }
            }
        }

        dialog.setOnDismissListener {
            showingDialog = null
            if (closeEntryOnDismiss) closeEntry(activity)
        }
        showingDialog = WeakReference(dialog)
        dialog.show()
    }

    private fun isDialogShowing(): Boolean = showingDialog?.get()?.isShowing == true

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun fieldLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = LABEL_TEXT_SIZE_SP
    }

    companion object {
        private const val TAG = "PikPak"
        private const val DIALOG_PADDING_DP = 20
        private const val LINKS_MIN_LINES = 4
        private const val LINKS_MAX_LINES = 8
        private const val LABEL_TEXT_SIZE_SP = 14f
        private const val ROW_TEXT_SIZE_SP = 14f
        private const val SMALL_TEXT_SIZE_SP = 13f
        private const val ACTIVITY_RETRY_MS = 400L
        private const val MAX_ACTIVITY_RETRIES = 5
    }
}

/** Name shown for a download row or episode until PikPak reports the real file name. */
internal fun PikPakCloudDownloads.Record.displayTitle(strings: PikPakStrings): String {
    pendingName?.takeIf { it.isNotBlank() }?.let { return it }
    name.takeIf { it.isNotBlank() }?.let { return it }
    PikPakPrefs.cloudLinkLabel(url)?.let { return it }
    return if (url.startsWith("magnet:", ignoreCase = true)) {
        strings.cloudMagnetLink()
    } else {
        strings.cloudUnknownLink()
    }
}

/** Progress/state line shared by settings rows and the cloud downloads description. */
internal fun PikPakCloudDownloads.Record.statusLabel(strings: PikPakStrings): String {
    val state = when {
        failed -> {
            val detail = message?.takeIf { it.isNotBlank() }
            if (detail == null) strings.cloudFailed() else "${strings.cloudFailed()}: $detail"
        }
        phase == PikPakCloudDownloads.PHASE_COMPLETE -> strings.cloudComplete()
        percent > 0 -> strings.cloudProgress(percent)
        else -> strings.cloudPending()
    }

    // A name or folder chosen while the download runs is shown as queued until it is written.
    val queued = when {
        pendingName?.isNotBlank() == true -> strings.cloudRenameQueued()
        pendingDestination?.isNotBlank() == true -> strings.cloudMoveQueued()
        else -> null
    }
    return if (queued == null || finished) state else "$state · $queued"
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
