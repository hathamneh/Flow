package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccount
import io.github.aedev.flow.ui.components.settings.SettingsGroupScope
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.screens.settings.index.YouTubeAccountIndex

private val AvatarSize = 40.dp

/** Google accounts for history sync: add several, switch between them, or use Flow as a guest. */
@Composable
internal fun YouTubeAccountScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: YouTubeAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val active = state.active
    if (showDiagnostics && active != null) {
        YouTubeSyncDiagnosticsScreen(accountName = active.name, onBack = { showDiagnostics = false })
        return
    }
    val syncingLabel = stringResource(R.string.youtube_account_syncing)
    val labels =
        AccountRowLabels(
            active = stringResource(R.string.youtube_account_active),
            needsSignIn = stringResource(R.string.youtube_account_needs_sign_in),
        )

    SettingsPage(
        title = stringResource(R.string.youtube_account_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        group(key = "youtube_account.accounts", header = R.string.youtube_account_accounts) {
            state.accounts.forEach { account ->
                accountRow(account, isActive = account.id == state.activeId, labels) { selectedId = account.id }
            }
            nav(
                YouTubeAccountIndex.guest,
                icon = Icons.Outlined.PersonOff,
                selected = state.activeId == null,
                showChevron = false,
                onClick = { viewModel.switchTo(null) },
            )
            nav(YouTubeAccountIndex.add, icon = Icons.Outlined.PersonAdd, showChevron = false, onClick = viewModel::startSignIn)
        }
        group(key = "youtube_account.sync", header = R.string.youtube_account_sync) {
            switch(YouTubeAccountIndex.reportHistory, state.reportHistory, viewModel::setReportHistory, enabled = state.active != null)
            nav(
                YouTubeAccountIndex.syncNow,
                icon = Icons.Outlined.Sync,
                value = if (syncing) syncingLabel else null,
                enabled = state.active != null && !syncing,
                showChevron = false,
                onClick = viewModel::syncNow,
            )
        }
        if (BuildConfig.YOUTUBE_SYNC_DIAGNOSTICS && active != null) {
            group(key = "youtube_account.debug") {
                row("youtube_account.diagnostics") { shape ->
                    FlowNavRow(
                        title = stringResource(R.string.yt_probe_title),
                        supportingText = stringResource(R.string.yt_probe_entry_summary),
                        leadingIcon = Icons.Outlined.BugReport,
                        shape = shape,
                        onClick = { showDiagnostics = true },
                    )
                }
            }
        }
    }

    signIn?.let { progress ->
        YouTubeSignInDialog(progress, scope, onRetry = viewModel::startSignIn, onDismiss = viewModel::dismissSignIn)
    }

    state.accounts.firstOrNull { it.id == selectedId }?.let { account ->
        YouTubeAccountActionsDialog(
            account = account,
            isActive = account.id == state.activeId,
            onUse = {
                viewModel.switchTo(account.id)
                selectedId = null
            },
            onSignInAgain = {
                selectedId = null
                viewModel.startSignIn()
            },
            onRemove = {
                viewModel.remove(account.id)
                selectedId = null
            },
            onDismiss = { selectedId = null },
        )
    }
}

private class AccountRowLabels(
    val active: String,
    val needsSignIn: String,
)

private fun SettingsGroupScope.accountRow(
    account: YouTubeAccount,
    isActive: Boolean,
    labels: AccountRowLabels,
    onClick: () -> Unit,
) = row("youtube_account.account.${account.id}") { shape ->
    val status =
        when {
            account.needsSignIn -> labels.needsSignIn
            isActive -> labels.active
            else -> null
        }
    FlowNavRow(
        title = account.name,
        supportingText = listOfNotNull(account.subtitle.takeIf { it.isNotBlank() }, status).joinToString(" · "),
        selected = isActive,
        showChevron = false,
        shape = shape,
        onClick = onClick,
        leadingContent = { AccountAvatar(account) },
    )
}

@Composable
internal fun AccountAvatar(
    account: YouTubeAccount,
    modifier: Modifier = Modifier,
) {
    if (account.avatarUrl.isBlank()) {
        Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = modifier.size(AvatarSize))
    } else {
        AsyncImage(
            model = account.avatarUrl,
            contentDescription = null,
            modifier = modifier.size(AvatarSize).clip(CircleShape),
        )
    }
}
