package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Settings screen for the PikPak source: the web login, session state and catalog options.
 *
 * Kept separate from [PikPak] so the catalog logic and the UI do not depend on each other.
 */
internal class PikPakPreferences(
    private val preferences: SharedPreferences,
    private val client: OkHttpClient,
    private val deviceId: () -> String,
    private val onSessionChanged: () -> Unit,
    private val onCatalogChanged: () -> Unit,
    private val checkExtraFolders: suspend (List<String>) -> List<Pair<String, Int?>>,
    private val cloudDownloads: PikPakCloudDownloads,
    private val cloudUi: PikPakCloudDownloadUi,
) {
    private val strings = PikPakStrings.get()

    private var downloadCategory: PreferenceCategory? = null
    private val downloadRows = mutableListOf<CloudDownloadRow>()
    private var renderedIds: List<String> = emptyList()

    fun setup(screen: PreferenceScreen) {
        val context = screen.context
        migrateExtraFolders()
        val loggedIn = hasSession()

        screen.addRow(strings.loginTitle(), strings.loginSummary(loggedIn)) {
            login(screen)
            true
        }

        screen.addRow(strings.statusTitle(), sessionStatus())

        if (loggedIn) {
            screen.addRow(strings.logoutTitle(), strings.logoutSummary()) {
                confirmLogout(screen)
                true
            }
        }

        screen.addRow(strings.cloudTitle(), strings.cloudSummary()) {
            cloudUi.show(context)
            true
        }

        // Add the category to the screen before adding rows: a group needs a PreferenceManager
        // before it can accept children, otherwise addPreference crashes on a null manager.
        val cloudCategory = PreferenceCategory(context).apply {
            title = strings.cloudSectionTitle()
        }
        screen.addPreference(cloudCategory)
        downloadCategory = cloudCategory

        renderDownloads(cloudDownloads.load())
        cloudDownloads.watch(::renderDownloads) {
            // Polling pauses once the settings page is no longer on screen; reopening resumes it.
            downloadRows.any { it.isOnScreen }
        }

        SwitchPreferenceCompat(context).apply {
            key = PikPakPrefs.TORRENTS
            title = strings.torrentsTitle()
            summary = strings.torrentsSummary()
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(PikPakPrefs.TORRENTS, newValue as Boolean).apply()
                onCatalogChanged()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(context).apply {
            key = PikPakPrefs.WEB_DOWNLOADS
            title = strings.downloadsTitle()
            summary = strings.downloadsSummary()
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(PikPakPrefs.WEB_DOWNLOADS, newValue as Boolean).apply()
                onCatalogChanged()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(context).apply {
            key = PikPakPrefs.FLAT_FOLDERS
            title = strings.flatFoldersTitle()
            summary = strings.flatFoldersSummary()
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(PikPakPrefs.FLAT_FOLDERS, newValue as Boolean).apply()
                onCatalogChanged()
                true
            }
        }.also(screen::addPreference)

        val resolutionEntries = listOf(strings.qualityAuto(), strings.qualityOrigin()) +
            QualitySelector.COMMON_RESOLUTIONS
        val resolutionValues = listOf(QualitySelector.AUTO, QualitySelector.ORIGIN) +
            QualitySelector.COMMON_RESOLUTIONS
        ListPreference(context).apply {
            key = PikPakPrefs.QUALITY
            title = strings.qualityTitle()
            summary = strings.qualitySummary()
            entries = resolutionEntries.toTypedArray()
            entryValues = resolutionValues.toTypedArray()
            setDefaultValue(QualitySelector.AUTO)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PikPakPrefs.QUALITY, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = PikPakPrefs.EXTRA_FOLDERS
            title = strings.extraFoldersTitle()
            summary = strings.extraFoldersSummary()
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val folders = PikPakPrefs.parseExtraFolders(newValue as String)
                // Normalise to one entry per line so names containing commas stay intact.
                preferences.edit()
                    .putString(PikPakPrefs.EXTRA_FOLDERS, folders.joinToString("\n"))
                    .putBoolean(PikPakPrefs.EXTRA_FOLDERS_LINES, true)
                    .apply()
                onCatalogChanged()
                true
            }
        }.also(screen::addPreference)

        screen.addRow(strings.checkExtraFoldersTitle(), strings.checkExtraFoldersSummary()) {
            checkFolders(screen)
            true
        }

        SwitchPreferenceCompat(context).apply {
            key = PikPakPrefs.METADATA
            title = strings.metadataTitle()
            summary = strings.metadataSummary()
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(PikPakPrefs.METADATA, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = PikPakPrefs.TMDB_KEY
            title = strings.tmdbTitle()
            summary = strings.tmdbSummary()
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PikPakPrefs.TMDB_KEY, (newValue as String).trim()).apply()
                true
            }
        }.also(screen::addPreference)

        screen.addRow(strings.infoFolderTitle(), strings.infoFolderSummary())
        screen.addRow(strings.infoSeasonTitle(), strings.infoSeasonSummary())
        screen.addRow(strings.infoEpisodeTitle(), strings.infoEpisodeSummary())
        screen.addRow(strings.infoMetadataTitle(), strings.infoMetadataSummary())
    }

    // =============================== Session ==============================

    /** Converts the comma separated form written by older versions to one entry per line. */
    private fun migrateExtraFolders() {
        if (preferences.getBoolean(PikPakPrefs.EXTRA_FOLDERS_LINES, false)) return

        val raw = preferences.getString(PikPakPrefs.EXTRA_FOLDERS, "").orEmpty()
        val migrated = PikPakPrefs.splitLegacyExtraFolders(raw)
        preferences.edit()
            .putString(PikPakPrefs.EXTRA_FOLDERS, migrated.joinToString("\n"))
            .putBoolean(PikPakPrefs.EXTRA_FOLDERS_LINES, true)
            .apply()
    }

    private fun hasSession(): Boolean {
        val stored = preferences.getString(PikPakPrefs.TOKEN_CLIENT, "").orEmpty() == PikPakPrefs.TOKEN_STORE_VERSION &&
            preferences.getString(PikPakPrefs.REFRESH_TOKEN, "").orEmpty().isNotBlank()
        val seed = preferences.getString(PikPakPrefs.SESSION_SEED, "").orEmpty().isNotBlank()
        return stored || seed
    }

    private fun sessionStatus(): String {
        if (!hasSession()) return strings.statusLoggedOut()

        val expiresAt = preferences.getLong(PikPakPrefs.TOKEN_EXPIRES_AT, 0L)
        val now = System.currentTimeMillis() / 1000
        return when {
            expiresAt <= 0L -> strings.statusLoggedIn()
            expiresAt <= now -> strings.statusExpired()
            else -> strings.statusExpires(PikPakPrefs.formatDuration(expiresAt - now))
        }
    }

    private fun importSession(session: PikPakClient.StoredTokens) {
        preferences.edit()
            .putString(PikPakPrefs.TOKEN_CLIENT, PikPakPrefs.TOKEN_STORE_VERSION)
            .putString(PikPakPrefs.ACCESS_TOKEN, TokenCipher.encrypt(session.accessToken))
            .putString(PikPakPrefs.REFRESH_TOKEN, TokenCipher.encrypt(session.refreshToken))
            .putString(PikPakPrefs.USER_ID, session.userId)
            .putLong(PikPakPrefs.TOKEN_EXPIRES_AT, session.expiresAt)
            .remove(PikPakPrefs.SESSION_SEED)
            .apply()
        onSessionChanged()
    }

    private fun logout(screen: PreferenceScreen) {
        preferences.edit()
            .remove(PikPakPrefs.TOKEN_CLIENT)
            .remove(PikPakPrefs.ACCESS_TOKEN)
            .remove(PikPakPrefs.REFRESH_TOKEN)
            .remove(PikPakPrefs.USER_ID)
            .remove(PikPakPrefs.TOKEN_EXPIRES_AT)
            .remove(PikPakPrefs.SESSION_SEED)
            .apply()
        clearWebSession()
        onSessionChanged()
        Toast.makeText(screen.context, strings.loggedOut(), Toast.LENGTH_SHORT).show()
    }

    /**
     * PikPak's web app keeps its session in cookies and page storage. Both are cleared so logging
     * out really signs the WebView out and the next login does not silently re-import the session.
     */
    private fun clearWebSession() {
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.getCookie(WEB_LOGIN_URL)?.split(';')?.forEach { entry ->
                val name = entry.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    cookies.setCookie(WEB_LOGIN_URL, "$name=; Max-Age=0; Path=/")
                }
            }
            cookies.flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    // ================================ Login ===============================

    private fun login(screen: PreferenceScreen) {
        val context = screen.context
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, strings.loginFailed(), Toast.LENGTH_SHORT).show()
            return
        }

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_UA
            webViewClient = WebViewClient()
        }

        // The login page is full screen, so pad the container by the current system bar insets
        // instead of letting the page start underneath the status bar. Platform APIs are used
        // because the host app may strip unused androidx.core methods.
        val container = FrameLayout(activity).apply { addView(webView) }
        container.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.setContentView(container)
        dialog.window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Handle the status/navigation bar insets ourselves so the page cannot draw under them.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false)
            }
        }

        val handler = Handler(Looper.getMainLooper())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val startedAt = System.currentTimeMillis()
        var attemptedToken: String? = null
        var checking = false
        var hinted = false

        lateinit var poll: Runnable
        poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return

                if (!hinted && System.currentTimeMillis() - startedAt > LOGIN_HINT_MS) {
                    hinted = true
                    Toast.makeText(activity, strings.loginWaiting(), Toast.LENGTH_LONG).show()
                    webView.evaluateJavascript(STORAGE_KEYS_JS) { keys ->
                        Log.d(TAG, "login storage keys=$keys")
                    }
                }

                webView.evaluateJavascript(READ_CREDENTIALS_JS) { raw ->
                    val token = PikPakPrefs.parseRefreshToken(
                        PikPakPrefs.decodeWebValue(decodeJavascriptString(raw).orEmpty()),
                    )
                    if (token == null || checking || token == attemptedToken) {
                        handler.postDelayed(poll, WEB_LOGIN_POLL_MS)
                        return@evaluateJavascript
                    }

                    attemptedToken = token
                    checking = true
                    Log.d(TAG, "login token found, validating")

                    scope.launch {
                        val session = runCatching {
                            // A throwaway client rotates the token, so a stale one never gets saved.
                            PikPakClient(client, deviceId(), strings, refreshSeed = token).refreshSession()
                        }.getOrNull()

                        withContext(Dispatchers.Main) {
                            checking = false
                            if (session == null) {
                                Log.d(TAG, "login token rejected, still polling")
                                handler.postDelayed(poll, WEB_LOGIN_POLL_MS)
                                return@withContext
                            }

                            val imported = runCatching { importSession(session) }.isSuccess
                            if (!imported) {
                                Log.e(TAG, "login import failed")
                                Toast.makeText(activity, strings.loginFailed(), Toast.LENGTH_SHORT).show()
                                if (dialog.isShowing) dialog.dismiss()
                                return@withContext
                            }

                            Log.d(TAG, "login imported")
                            if (dialog.isShowing) {
                                Toast.makeText(activity, strings.loginImported(), Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
        }

        dialog.setOnShowListener {
            // Insets are dispatched once the dialog window is attached; re-request them so the
            // initial layout already has the status bar margin.
            container.requestApplyInsets()
        }

        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            scope.cancel()
            webView.destroy()
        }

        dialog.show()
        webView.loadUrl(WEB_LOGIN_URL)
        handler.postDelayed(poll, WEB_LOGIN_POLL_MS)
    }

    // ============================ Extra folders ===========================

    private fun checkFolders(screen: PreferenceScreen) {
        val context = screen.context
        val folders = PikPakPrefs.parseExtraFolders(preferences.getString(PikPakPrefs.EXTRA_FOLDERS, null))
        if (folders.isEmpty()) {
            Toast.makeText(context, strings.extraFoldersNone(), Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, strings.extraFoldersChecking(), Toast.LENGTH_SHORT).show()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val report = runCatching { checkExtraFolders(folders) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                val text = if (report.isEmpty()) {
                    strings.extraFoldersNone()
                } else {
                    report.joinToString("\n") { (input, count) ->
                        if (count == null) {
                            strings.extraFoldersMissing(input)
                        } else {
                            strings.extraFoldersReport(input, count)
                        }
                    }
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                scope.cancel()
            }
        }
    }

    // =========================== Cloud downloads ==========================

    /** Renders the persisted downloads as rows of the cloud download category. */
    private fun renderDownloads(records: List<PikPakCloudDownloads.Record>) {
        val category = downloadCategory ?: return
        if (records.isEmpty()) {
            downloadRows.forEach(category::removePreference)
            downloadRows.clear()
            renderedIds = emptyList()
            return
        }

        if (!sameRecords(records)) {
            downloadRows.forEach(category::removePreference)
            downloadRows.clear()

            records.forEach {
                CloudDownloadRow(category.context).apply {
                    setOnPreferenceClickListener {
                        val current = record
                        if (current != null && !current.failed) {
                            cloudUi.showEdit(category.context, current)
                        }
                        true
                    }
                }.also { row ->
                    category.addPreference(row)
                    downloadRows += row
                }
            }
            renderedIds = records.map { it.identity() }
        }

        records.forEachIndexed { index, record ->
            downloadRows.getOrNull(index)?.let { bindDownloadRow(it, record) }
        }
    }

    private fun sameRecords(records: List<PikPakCloudDownloads.Record>): Boolean = renderedIds == records.map { it.identity() }

    private fun PikPakCloudDownloads.Record.identity(): String = "$taskId|$url"

    private fun bindDownloadRow(row: CloudDownloadRow, record: PikPakCloudDownloads.Record) {
        row.record = record
        // Every row opens the editor: finished downloads are renamed right away, running ones
        // when they finish.
        row.isSelectable = !record.failed
        row.title = record.displayTitle(strings)
        val destination = record.destination.ifBlank { strings.cloudDestinationHint() }
        row.summary = "${record.statusLabel(strings)} · $destination"
    }

    /** Logging out destroys the saved session, so it always asks first. */
    private fun confirmLogout(screen: PreferenceScreen) {
        AlertDialog.Builder(screen.context)
            .setTitle(strings.logoutTitle())
            .setMessage(strings.logoutConfirmMessage())
            .setNegativeButton(strings.cloudCancelAction(), null)
            .setPositiveButton(strings.logoutAction()) { _, _ -> logout(screen) }
            .show()
    }

    private fun PreferenceGroup.addRow(
        rowTitle: String,
        rowSummary: String,
        onClick: (() -> Boolean)? = null,
    ) {
        val row = Preference(context).apply {
            title = rowTitle
            summary = rowSummary
            onClick?.let { setOnPreferenceClickListener { it() } }
        }
        addPreference(row)
    }

    /** A download row; the view binding tells the poller whether the settings page is on screen. */
    private class CloudDownloadRow(context: Context) : Preference(context) {
        var record: PikPakCloudDownloads.Record? = null
        private var boundView: View? = null

        init {
            isPersistent = false
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            boundView = holder.itemView
        }

        val isOnScreen: Boolean
            get() = boundView?.isShown == true && boundView?.isAttachedToWindow == true
    }

    companion object {
        private const val TAG = "PikPak"

        private const val WEB_LOGIN_URL = "https://mypikpak.com/"
        private const val WEB_LOGIN_POLL_MS = 1_500L
        private const val LOGIN_HINT_MS = 25_000L

        // Only read storage on PikPak's own origin, and prefer the credentials entry.
        private const val READ_CREDENTIALS_JS =
            "(function(){" +
                "try{" +
                "var host=location.host||'';" +
                "if(host.indexOf('mypikpak.com')<0)return null;" +
                "var best=localStorage.getItem('credentials');" +
                "if(best&&best.indexOf('refresh_token')>=0)return best;" +
                "var stores=[localStorage,sessionStorage];" +
                "for(var s=0;s<stores.length;s++){" +
                "var st=stores[s];" +
                "for(var i=0;i<st.length;i++){" +
                "var v=st.getItem(st.key(i));" +
                "if(v&&v.indexOf('refresh_token')>=0)return v;" +
                "}" +
                "}" +
                "return best;" +
                "}catch(e){return null}" +
                "})()"

        // Key names only, never values; used to diagnose where PikPak keeps the session.
        private const val STORAGE_KEYS_JS =
            "(function(){" +
                "try{" +
                "var out=[];" +
                "for(var i=0;i<localStorage.length;i++)out.push(localStorage.key(i));" +
                "var s=[];" +
                "for(var j=0;j<sessionStorage.length;j++)s.push(sessionStorage.key(j));" +
                "return JSON.stringify({local:out,session:s});" +
                "}catch(e){return null}" +
                "})()"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

internal fun decodeJavascriptString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
}
