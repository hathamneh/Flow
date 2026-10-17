package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object YouTubeAccountIndex {
    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "youtube_account.$key",
        title = title,
        summary = summary,
        keywords = R.string.settings_keywords_youtube_account,
        section = section,
        destination = SettingsDestination.YOUTUBE_ACCOUNT,
    )

    val guest = entry("guest", R.string.youtube_account_guest, R.string.youtube_account_accounts, R.string.youtube_account_guest_summary)
    val add = entry("add", R.string.youtube_account_add, R.string.youtube_account_accounts, R.string.youtube_account_add_summary)
    val reportHistory =
        entry(
            "report_history",
            R.string.youtube_account_report_history,
            R.string.youtube_account_sync,
            R.string.youtube_account_report_history_summary,
        )
    val syncNow =
        entry("sync_now", R.string.youtube_account_sync_now, R.string.youtube_account_sync, R.string.youtube_account_sync_now_summary)

    val all = listOf(guest, add, reportHistory, syncNow)
}
