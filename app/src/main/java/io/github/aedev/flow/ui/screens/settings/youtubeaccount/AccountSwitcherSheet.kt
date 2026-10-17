package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.SyncProgress
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccount
import io.github.aedev.flow.ui.components.account.AccountPicture

private val ButtonAvatarSize = 30.dp
private val HeaderAvatarSize = 72.dp
private val RowAvatarSize = 40.dp

/**
 * The account button in the settings bar: the active account's picture (a person outline for Guest).
 * It opens [AccountSwitcherSheet], with sign-in shown as a dialog over it.
 */
@Composable
internal fun AccountButton(
    viewModel: YouTubeAccountViewModel,
    onManage: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    IconButton(onClick = { open = true }) {
        AccountPicture(state.active, ButtonAvatarSize, contentDescription = stringResource(R.string.profile_account_button))
    }
    if (open) {
        AccountSwitcherSheet(
            viewModel = viewModel,
            onManage = {
                open = false
                onManage()
            },
            onDismiss = { open = false },
        )
    }
    signIn?.let { progress ->
        YouTubeSignInDialog(progress, scope, onRetry = viewModel::startSignIn, onDismiss = viewModel::dismissSignIn)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSwitcherSheet(
    viewModel: YouTubeAccountViewModel,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val manualSync by viewModel.syncing.collectAsStateWithLifecycle()
    val progress by viewModel.syncProgress.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<YouTubeAccount?>(null) }
    val active = state.active

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            AccountHeader(
                account = active,
                progress = progress,
                syncing = manualSync || progress != null,
                onSync = viewModel::syncNow,
                onRemove = { confirmRemove = active },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val others = state.accounts.filterNot { it.id == state.activeId }
            others.forEach { account ->
                SheetRow(
                    title = account.name,
                    subtitle =
                        if (account.needsSignIn) {
                            stringResource(R.string.youtube_account_needs_sign_in)
                        } else {
                            account.subtitle.takeIf { it.isNotBlank() }
                        },
                    leading = { AccountPicture(account, RowAvatarSize) },
                    onClick = {
                        if (account.needsSignIn) {
                            viewModel.startSignIn()
                        } else {
                            onDismiss()
                            viewModel.switchTo(account.id)
                        }
                    },
                )
            }
            if (active != null) {
                SheetRow(
                    title = stringResource(R.string.profile_guest),
                    subtitle = stringResource(R.string.profile_guest_summary),
                    leading = { AccountPicture(null, RowAvatarSize) },
                    onClick = {
                        onDismiss()
                        viewModel.switchTo(null)
                    },
                )
            }
            SheetRow(
                title = stringResource(R.string.profile_add_another),
                subtitle = stringResource(R.string.profile_add_summary),
                leading = { RowIcon(Icons.Outlined.PersonAdd) },
                onClick = viewModel::startSignIn,
            )
            SheetRow(
                title = stringResource(R.string.profile_manage),
                subtitle = null,
                leading = { RowIcon(Icons.Outlined.ManageAccounts) },
                onClick = onManage,
            )
            Text(
                text = stringResource(R.string.profile_switch_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }

    confirmRemove?.let { account ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            icon = { Icon(Icons.Outlined.PersonRemove, contentDescription = null) },
            title = { Text(stringResource(R.string.profile_remove_confirm_title, account.name)) },
            text = { Text(stringResource(R.string.profile_remove_confirm_body, account.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        onDismiss()
                        viewModel.remove(account.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.youtube_account_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AccountHeader(
    account: YouTubeAccount?,
    progress: SyncProgress?,
    syncing: Boolean,
    onSync: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AccountPicture(account, HeaderAvatarSize)
        Spacer(Modifier.height(8.dp))
        Text(
            text = account?.name ?: stringResource(R.string.profile_guest),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = account?.subtitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.profile_guest_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (account == null) return@Column
        if (account.needsSignIn) {
            Text(
                text = stringResource(R.string.youtube_account_needs_sign_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onSync, enabled = !syncing && !account.needsSignIn) {
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(if (syncing) R.string.youtube_account_syncing else R.string.youtube_account_sync_now))
            }
            OutlinedButton(onClick = onRemove) {
                Icon(Icons.Outlined.PersonRemove, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.profile_remove_short))
            }
        }
        progress?.let {
            Text(
                text = stringResource(syncStepLabel(it.phase)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun syncStepLabel(phase: SyncProgress.Phase): Int =
    when (phase) {
        SyncProgress.Phase.HISTORY -> R.string.profile_import_history
        SyncProgress.Phase.SUBSCRIPTIONS -> R.string.profile_import_subscriptions
        SyncProgress.Phase.LIBRARY -> R.string.profile_import_library
    }

@Composable
private fun SheetRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = leading,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(RowAvatarSize).padding(8.dp))
}
