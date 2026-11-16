package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.content.res.Resources
import androidx.annotation.StringRes
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.R
import keiyoushi.utils.applicationContext
import java.util.Locale

/**
 * Localised user-facing strings.
 *
 * Extension code runs inside the host app, whose context resolves against the host's resource
 * table, so the extension's own resources are looked up through its installed package instead.
 * If that is not possible (package visibility, older hosts) the English text below is used.
 */
internal class PikPakStrings private constructor(private val resources: Resources?) {
    // ================================ Login ================================

    fun loginTitle(): String = get(R.string.pikpak_login_title, "Log in with PikPak (web)")

    fun loginSummary(loggedIn: Boolean): String = if (loggedIn) {
        get(
            R.string.pikpak_login_summary_logged_in,
            "Logged in. Tap here to replace the saved PikPak session.",
        )
    } else {
        get(
            R.string.pikpak_login_summary,
            "Tap here to open PikPak's website and import the session automatically.",
        )
    }

    fun logoutTitle(): String = get(R.string.pikpak_logout_title, "Log out of PikPak")

    fun logoutSummary(): String = get(
        R.string.pikpak_logout_summary,
        "Forget the saved session and sign out of the PikPak page. Your files are not touched.",
    )

    fun logoutConfirmMessage(): String = get(
        R.string.pikpak_logout_confirm_message,
        "Forget the saved PikPak session? Your files are not touched.",
    )

    fun logoutAction(): String = get(R.string.pikpak_logout_action, "Log out")

    fun statusTitle(): String = get(R.string.pikpak_status_title, "Session")

    fun statusLoggedOut(): String = get(R.string.pikpak_status_logged_out, "Not logged in.")

    fun statusLoggedIn(): String = get(R.string.pikpak_status_logged_in, "Logged in.")

    fun statusExpired(): String = get(
        R.string.pikpak_status_expired,
        "Logged in. The access token is being refreshed.",
    )

    fun statusExpires(duration: String): String = get(
        R.string.pikpak_status_expires,
        "Logged in. The session refreshes automatically (valid for %1\$s).",
        duration,
    )

    // ================================ Info =================================

    fun infoFolderTitle(): String = get(R.string.pikpak_info_folder_title, "Folder structure")

    fun infoFolderSummary(): String = get(
        R.string.pikpak_info_folder_summary,
        "Each folder directly inside My Pack is one anime. Its sub-folders become seasons, and a " +
            "folder without sub-folders shows its episodes directly.",
    )

    fun infoSeasonTitle(): String = get(R.string.pikpak_info_season_title, "Season folders")

    fun infoSeasonSummary(): String = get(
        R.string.pikpak_info_season_summary,
        "Sub-folders inside an anime folder become seasons, and each season name is matched " +
            "against the API separately.",
    )

    fun infoEpisodeTitle(): String = get(R.string.pikpak_info_episode_title, "Episode files")

    fun infoEpisodeSummary(): String = get(
        R.string.pikpak_info_episode_summary,
        "Episode numbers are read from file names. Files without a number are numbered in name order.",
    )

    fun infoMetadataTitle(): String = get(R.string.pikpak_info_metadata_title, "Metadata")

    fun infoMetadataSummary(): String = get(
        R.string.pikpak_info_metadata_summary,
        "Anime folder names are matched against AniList for cover, studio, characters, " +
            "recommendations and related entries. Add a TMDB API key for non-anime movies and TV.",
    )

    // ============================== Settings ===============================

    fun torrentsTitle(): String = get(R.string.pikpak_torrents_title, "Show torrent downloads")

    fun torrentsSummary(): String = get(
        R.string.pikpak_torrents_summary,
        "Items in My Pack that were added from magnet links.",
    )

    fun downloadsTitle(): String = get(R.string.pikpak_downloads_title, "Show web downloads")

    fun downloadsSummary(): String = get(
        R.string.pikpak_downloads_summary,
        "Files in My Pack that were not added from magnet links.",
    )

    fun flatFoldersTitle(): String = get(R.string.pikpak_flat_folders_title, "Detect flat folders")

    fun flatFoldersSummary(): String = get(
        R.string.pikpak_flat_folders_summary,
        "Probe the folders on a catalog page so a folder without sub-folders opens straight into " +
            "episodes. Each folder is probed once and the answer is remembered.",
    )

    fun qualityTitle(): String = get(R.string.pikpak_quality_title, "Preferred resolution")

    fun qualitySummary(): String = get(
        R.string.pikpak_quality_summary,
        "Only this resolution is offered for playback and downloads. When it is missing, the " +
            "closest higher resolution is used, then lower ones.",
    )

    fun qualityAuto(): String = get(R.string.pikpak_quality_auto, "Automatic (all streams)")

    fun qualityOrigin(): String = get(R.string.pikpak_quality_origin, "Original")

    fun extraFoldersTitle(): String = get(R.string.pikpak_extra_folders_title, "Extra folders")

    fun extraFoldersSummary(): String = get(
        R.string.pikpak_extra_folders_summary,
        "Anime folders inside these are listed at the top of the catalog. One folder ID or path per " +
            "line. Example:\nMy Shared/Anime",
    )

    fun checkExtraFoldersTitle(): String = get(
        R.string.pikpak_extra_folders_check_title,
        "Check extra folders",
    )

    fun checkExtraFoldersSummary(): String = get(
        R.string.pikpak_extra_folders_check_summary,
        "Resolve the configured folders and report how many anime each one contains.",
    )

    fun extraFoldersNone(): String = get(R.string.pikpak_extra_folders_none, "No extra folders are configured.")

    fun extraFoldersChecking(): String = get(R.string.pikpak_extra_folders_checking, "Checking extra folders…")

    fun extraFoldersReport(input: String, count: Int): String = get(
        R.string.pikpak_extra_folders_report,
        "%1\$s: %2\$d anime",
        input,
        count,
    )

    fun extraFoldersMissing(input: String): String = get(
        R.string.pikpak_extra_folders_missing,
        "%1\$s: not found",
        input,
    )

    fun metadataTitle(): String = get(R.string.pikpak_metadata_title, "Fetch anime metadata")

    fun metadataSummary(): String = get(
        R.string.pikpak_metadata_summary,
        "Use AniList for covers, studio, characters and suggestions. TMDB is used as fallback.",
    )

    fun tmdbTitle(): String = get(R.string.pikpak_tmdb_title, "TMDB API key (optional)")

    fun tmdbSummary(): String = get(
        R.string.pikpak_tmdb_summary,
        "Used when AniList has no match, especially for movies and TV shows.",
    )

    // ========================== Cloud downloads ===========================

    fun cloudTitle(): String = get(R.string.pikpak_cloud_title, "Add Cloud Download")

    fun cloudSectionTitle(): String = get(R.string.pikpak_cloud_section, "Cloud downloads")

    fun cloudEntryTitle(): String = get(R.string.pikpak_cloud_entry_title, "Cloud downloads")

    fun cloudEntryDescription(): String = get(
        R.string.pikpak_cloud_entry_description,
        "Tap Add Cloud Download to save magnet or video links. Tap a download to rename it — " +
            "running ones are renamed when they finish.",
    )

    fun cloudEntryNoDownloads(): String = get(R.string.pikpak_cloud_entry_no_downloads, "No downloads yet.")

    fun cloudEntryTapHint(): String = get(
        R.string.pikpak_cloud_entry_tap_hint,
        "Tap a download to rename or move it.",
    )

    fun cloudDialogTitle(): String = get(R.string.pikpak_cloud_dialog_title, "Cloud downloads")

    fun cloudEntryDownloadsHeader(): String = get(R.string.pikpak_cloud_entry_downloads_header, "Downloads")

    fun cloudEntryMore(count: Int): String = get(
        R.string.pikpak_cloud_entry_more,
        "…and %1\$d more",
        count,
    )

    fun cloudOpenSettingsHint(): String = get(
        R.string.pikpak_cloud_open_settings_hint,
        "Open the PikPak settings and tap Add Cloud Download.",
    )

    fun cloudSummary(): String = get(
        R.string.pikpak_cloud_summary,
        "Send magnet or video links to PikPak and follow their progress here.",
    )

    fun cloudLinksLabel(): String = get(R.string.pikpak_cloud_links_label, "Links")

    fun cloudLinksHint(): String = get(R.string.pikpak_cloud_links_hint, "One link per line")

    fun cloudNamesLabel(): String = get(
        R.string.pikpak_cloud_names_label,
        "Names (optional, one per link)",
    )

    fun cloudNamesHint(): String = get(
        R.string.pikpak_cloud_names_hint,
        "Leave a line blank to keep the original name",
    )

    fun cloudDestinationLabel(): String = get(R.string.pikpak_cloud_destination_label, "Download to")

    fun cloudDestinationHint(): String = get(R.string.pikpak_cloud_destination_hint, "My Pack")

    fun cloudDownloadAction(): String = get(R.string.pikpak_cloud_download, "Download")

    fun cloudCancelAction(): String = get(R.string.pikpak_cloud_cancel, "Cancel")

    fun cloudLinksEmpty(): String = get(R.string.pikpak_cloud_links_empty, "Add at least one link.")

    fun cloudQueued(count: Int): String = get(
        R.string.pikpak_cloud_queued,
        "Adding %1\$d download(s)…",
        count,
    )

    fun cloudPending(): String = get(R.string.pikpak_cloud_pending, "Queued")

    fun cloudProgress(percent: Int): String = get(
        R.string.pikpak_cloud_progress,
        "Downloading %1\$d%%",
        percent,
    )

    fun cloudComplete(): String = get(R.string.pikpak_cloud_complete, "Download complete")

    fun cloudFailed(): String = get(R.string.pikpak_cloud_failed, "Download failed")

    fun cloudUnknownLink(): String = get(R.string.pikpak_cloud_unknown_link, "Link")

    fun cloudMagnetLink(): String = get(R.string.pikpak_cloud_magnet_link, "Magnet link")

    fun cloudDownloadFailed(code: Int, reason: String): String = get(
        R.string.pikpak_cloud_download_failed,
        "PikPak rejected the link (HTTP %1\$d): %2\$s",
        code,
        reason,
    )

    fun cloudRenameLabel(): String = get(R.string.pikpak_cloud_rename_label, "Name")

    fun cloudEditTitle(): String = get(R.string.pikpak_cloud_edit_title, "Edit Download")

    fun cloudEditAction(): String = get(R.string.pikpak_cloud_edit_action, "Save")

    fun cloudEditFolderLabel(): String = get(
        R.string.pikpak_cloud_edit_folder_label,
        "Folder (ID or path)",
    )

    fun cloudRenameEmpty(): String = get(R.string.pikpak_cloud_rename_empty, "Enter a name.")

    fun cloudEdited(): String = get(R.string.pikpak_cloud_edited, "Download updated")

    fun cloudEditQueued(): String = get(
        R.string.pikpak_cloud_edit_queued,
        "Name saved — it is applied when the download finishes.",
    )

    fun cloudEditPendingNote(): String = get(
        R.string.pikpak_cloud_edit_pending_note,
        "This download is still running. The new name is applied when it finishes.",
    )

    fun cloudRenameQueued(): String = get(R.string.pikpak_cloud_rename_queued, "rename queued")

    fun cloudMoveQueued(): String = get(R.string.pikpak_cloud_move_queued, "move queued")

    fun cloudEditFailed(): String = get(R.string.pikpak_cloud_edit_failed, "Could not update the download.")

    fun renameFailed(reason: String): String = get(
        R.string.pikpak_error_rename_failed,
        "PikPak could not rename the download: %1\$s",
        reason,
    )

    fun moveFailed(reason: String): String = get(
        R.string.pikpak_error_move_failed,
        "PikPak could not move the download: %1\$s",
        reason,
    )

    fun folderMissing(input: String): String = get(
        R.string.pikpak_error_folder_missing,
        "Folder not found: %1\$s",
        input,
    )

    // =============================== Toasts ================================

    fun loginFailed(): String = get(R.string.pikpak_toast_login_failed, "Could not open the PikPak login.")

    fun loginImported(): String = get(R.string.pikpak_toast_login_imported, "PikPak login imported")

    fun loginWaiting(): String = get(
        R.string.pikpak_toast_login_waiting,
        "Still waiting for the PikPak session. Finish signing in on the page.",
    )

    fun loggedOut(): String = get(R.string.pikpak_toast_logged_out, "Logged out of PikPak")

    // ============================ Descriptions =============================

    fun descriptionExtraFolder(): String = get(R.string.pikpak_desc_extra_folder, "PikPak extra folder")

    fun descriptionTorrent(): String = get(R.string.pikpak_desc_torrent, "PikPak torrent")

    fun descriptionFile(): String = get(R.string.pikpak_desc_file, "PikPak file")

    fun descriptionKind(kind: MediaKind): String = when (kind) {
        MediaKind.SERIES -> get(R.string.pikpak_desc_series, "Series")
        MediaKind.SEASON -> get(R.string.pikpak_desc_season, "Season")
        MediaKind.EPISODE -> get(R.string.pikpak_desc_episode, "Episode")
        MediaKind.MOVIE -> get(R.string.pikpak_desc_movie, "Movie")
        MediaKind.SPECIAL -> get(R.string.pikpak_desc_special, "Special")
        MediaKind.OVA -> get(R.string.pikpak_desc_ova, "OVA")
        MediaKind.ONA -> get(R.string.pikpak_desc_ona, "ONA")
        MediaKind.SHORT -> get(R.string.pikpak_desc_short, "Short")
        MediaKind.OTHER -> get(R.string.pikpak_desc_other, "Other")
    }

    // ============================== Errors =================================

    fun notLoggedIn(reason: String?): String = get(
        R.string.pikpak_error_not_logged_in,
        "PikPak is not logged in%1\$s. Use \"Log in with PikPak (web)\" in the extension settings.",
        suffix(reason),
    )

    fun sessionRejected(reason: String?): String = get(
        R.string.pikpak_error_session_rejected,
        "PikPak rejected the saved session%1\$s. Log in again with \"Log in with PikPak (web)\" in the extension settings.",
        suffix(reason),
    )

    fun myPackMissing(): String = get(R.string.pikpak_error_my_pack_missing, "PikPak 'My Pack' folder not found.")

    fun myPackFailed(error: String): String = get(
        R.string.pikpak_error_my_pack,
        "Could not list PikPak My Pack: %1\$s",
        error,
    )

    fun itemFailed(error: String): String = get(
        R.string.pikpak_error_item,
        "Could not list PikPak item: %1\$s",
        error,
    )

    fun streamFailed(error: String): String = get(
        R.string.pikpak_error_stream,
        "Could not generate a PikPak playback link: %1\$s",
        error,
    )

    fun noPlaybackLink(): String = get(
        R.string.pikpak_error_no_link,
        "PikPak did not return a playback link for this file.",
    )

    fun olderEpisodeList(): String = get(
        R.string.pikpak_error_older_episodes,
        "This episode list is from an older version. Refresh the anime.",
    )

    fun listError(error: String): String = get(R.string.pikpak_error_list, "PikPak list error: %1\$s", error)

    fun fileHttp(code: Int, body: String): String = get(
        R.string.pikpak_error_file_http,
        "PikPak get file HTTP %1\$d: %2\$s",
        code,
        body,
    )

    fun fileFailed(error: String, description: String): String = get(
        R.string.pikpak_error_file_failed,
        "PikPak get file failed: %1\$s %2\$s",
        error,
        description,
    )

    fun filesHttp(code: Int, body: String): String = get(
        R.string.pikpak_error_files_http,
        "PikPak list files HTTP %1\$d: %2\$s",
        code,
        body,
    )

    fun denied(code: Int): String = get(
        R.string.pikpak_error_denied,
        "PikPak denied the request (HTTP %1\$d).",
        code,
    )

    fun unavailable(code: Int): String = get(
        R.string.pikpak_error_unavailable,
        "PikPak is temporarily unavailable (HTTP %1\$d). Try again later.",
        code,
    )

    fun rateLimited(): String = get(
        R.string.pikpak_error_rate_limited,
        "PikPak is rate-limiting requests (\"Your operation is too frequent\"). Wait a while and try again.",
    )

    fun captchaHttp(code: Int, body: String): String = get(
        R.string.pikpak_error_captcha_http,
        "PikPak captcha HTTP %1\$d: %2\$s",
        code,
        body,
    )

    fun captchaFailed(error: String, description: String, url: String): String = get(
        R.string.pikpak_error_captcha_failed,
        "PikPak captcha failed: %1\$s %2\$s %3\$s",
        error,
        description,
        url,
    )

    // ============================== Helpers ================================

    @Suppress("SpreadOperator")
    private fun get(@StringRes id: Int, fallback: String, vararg args: Any): String {
        val resolved = resources?.let { res -> runCatching { res.getString(id, *args) }.getOrNull() }
        return resolved ?: String.format(Locale.getDefault(), fallback, *args)
    }

    private fun suffix(reason: String?): String = reason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    companion object {
        @Volatile
        private var instance: PikPakStrings? = null

        fun get(): PikPakStrings = instance ?: synchronized(this) {
            instance ?: PikPakStrings(resolveResources()).also { instance = it }
        }

        private fun resolveResources(): Resources? = runCatching {
            applicationContext.packageManager.getResourcesForApplication(BuildConfig.APPLICATION_ID)
        }.getOrNull()
    }
}
