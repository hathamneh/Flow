package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.YouTubeSignInProgress
import io.github.aedev.flow.ui.screens.settings.youtubeaccount.YouTubeAccountViewModel
import io.github.aedev.flow.ui.screens.settings.youtubeaccount.YouTubeSignInContent
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSelectionRow
import io.github.aedev.flow.ui.tv.components.TvToggleRow

private val QrSize = 220.dp

/**
 * Google accounts on TV: pick the active account (or none), add one with a code entered on a phone,
 * and sync. The same page as the phone's, laid out for the remote.
 */
@Composable
fun TvAccountSettingsPane(
    modifier: Modifier = Modifier,
    viewModel: YouTubeAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { message = it } }

    val progress = signIn
    if (progress != null) {
        TvSignInPanel(progress, onRetry = viewModel::startSignIn, onClose = viewModel::dismissSignIn, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val activeLabel = stringResource(R.string.youtube_account_active)
        val needsSignInLabel = stringResource(R.string.youtube_account_needs_sign_in)
        state.accounts.forEach { account ->
            val isActive = account.id == state.activeId
            val status =
                when {
                    account.needsSignIn -> needsSignInLabel
                    isActive -> activeLabel
                    else -> null
                }
            TvSelectionRow(
                label = account.name,
                supportingText = listOfNotNull(account.subtitle.takeIf { it.isNotBlank() }, status).joinToString(" · "),
                selected = isActive,
                onClick = { if (account.needsSignIn) viewModel.startSignIn() else viewModel.switchTo(account.id) },
            )
        }
        TvSelectionRow(
            label = stringResource(R.string.youtube_account_guest),
            supportingText = stringResource(R.string.youtube_account_guest_summary),
            selected = state.activeId == null,
            onClick = { viewModel.switchTo(null) },
        )
        TvNavRow(
            label = stringResource(R.string.youtube_account_add),
            supportingText = stringResource(R.string.youtube_account_add_summary),
            leadingIcon = Icons.Outlined.PersonAdd,
            onClick = viewModel::startSignIn,
        )
        state.active?.let { active ->
            TvToggleRow(
                label = stringResource(R.string.youtube_account_report_history),
                supportingText = stringResource(R.string.youtube_account_report_history_summary),
                checked = state.reportHistory,
                onCheckedChange = viewModel::setReportHistory,
            )
            TvNavRow(
                label = stringResource(R.string.youtube_account_sync_now),
                supportingText = stringResource(R.string.youtube_account_sync_now_summary),
                value = if (syncing) stringResource(R.string.youtube_account_syncing) else null,
                leadingIcon = Icons.Outlined.Sync,
                onClick = viewModel::syncNow,
            )
            TvNavRow(
                label = stringResource(R.string.youtube_account_remove),
                supportingText = active.name,
                leadingIcon = Icons.Outlined.PersonRemove,
                onClick = { viewModel.remove(active.id) },
            )
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TvSignInPanel(
    progress: YouTubeSignInProgress,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.youtube_account_sign_in_title), style = MaterialTheme.typography.headlineSmall)
        YouTubeSignInContent(progress, qrSize = QrSize, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress is YouTubeSignInProgress.Failed) {
                TvButton(text = stringResource(R.string.youtube_account_try_again), onClick = onRetry)
            }
            TvButton(
                text = stringResource(if (progress is YouTubeSignInProgress.Done) R.string.done else R.string.cancel),
                onClick = onClose,
            )
        }
    }
}
