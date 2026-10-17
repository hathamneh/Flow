package io.github.aedev.flow.ui.tv.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccount
import io.github.aedev.flow.ui.screens.settings.youtubeaccount.YouTubeAccountViewModel
import io.github.aedev.flow.ui.tv.screens.settings.TvSignInPanel

/**
 * Who is watching: every profile on this TV — one per Google account, plus Guest — and a way to add
 * one. Picking another profile restarts Flow into that person's own history, subscriptions and
 * suggestions.
 */
@Composable
fun BoxScope.TvProfilePanel(
    visible: Boolean,
    viewModel: YouTubeAccountViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<YouTubeAccount?>(null) }
    LaunchedEffect(visible) {
        removing = false
        confirming = null
    }
    BackHandler(enabled = visible && (removing || confirming != null)) {
        if (confirming != null) confirming = null else removing = false
    }

    TvSidePanel(
        visible = visible,
        title = stringResource(R.string.profile_picker_title),
        onClose = {
            viewModel.dismissSignIn()
            onClose()
        },
    ) {
        val progress = signIn
        if (progress != null) {
            TvSignInPanel(progress, onRetry = viewModel::startSignIn, onClose = viewModel::dismissSignIn)
            return@TvSidePanel
        }
        confirming?.let { account ->
            RemoveConfirmation(
                account = account,
                onRemove = {
                    confirming = null
                    removing = false
                    viewModel.remove(account.id)
                },
                onCancel = { confirming = null },
            )
            return@TvSidePanel
        }
        if (removing) {
            RemoveList(accounts = state.accounts, onPick = { confirming = it }, onDone = { removing = false })
            return@TvSidePanel
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val needsSignInLabel = stringResource(R.string.youtube_account_needs_sign_in)
            state.accounts.forEach { account ->
                TvSelectionRow(
                    label = account.name,
                    supportingText = if (account.needsSignIn) needsSignInLabel else account.subtitle.takeIf { it.isNotBlank() },
                    selected = account.id == state.activeId,
                    leading = { TvProfileAvatar(account, size = AvatarSize) },
                    onClick = {
                        when {
                            account.needsSignIn -> viewModel.startSignIn()
                            account.id == state.activeId -> onClose()
                            else -> viewModel.switchTo(account.id)
                        }
                    },
                )
            }
            TvSelectionRow(
                label = stringResource(R.string.profile_guest),
                supportingText = stringResource(R.string.profile_guest_summary),
                selected = state.activeId == null,
                leading = { TvProfileAvatar(null, size = AvatarSize) },
                onClick = { if (state.activeId == null) onClose() else viewModel.switchTo(null) },
            )
            TvNavRow(
                label = stringResource(R.string.youtube_account_add),
                supportingText = stringResource(R.string.profile_add_summary),
                leadingIcon = Icons.Outlined.PersonAdd,
                onClick = viewModel::startSignIn,
            )
            if (state.accounts.isNotEmpty()) {
                TvNavRow(
                    label = stringResource(R.string.profile_remove),
                    supportingText = stringResource(R.string.profile_remove_summary),
                    leadingIcon = Icons.Outlined.PersonRemove,
                    onClick = { removing = true },
                )
            }
            Text(
                text = stringResource(R.string.profile_switch_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoveList(
    accounts: List<YouTubeAccount>,
    onPick: (YouTubeAccount) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.profile_remove_pick),
            style = MaterialTheme.typography.titleMedium,
        )
        accounts.forEach { account ->
            TvSelectionRow(
                label = account.name,
                supportingText = account.subtitle.takeIf { it.isNotBlank() },
                selected = false,
                leading = { TvProfileAvatar(account, size = AvatarSize) },
                onClick = { onPick(account) },
            )
        }
        TvButton(text = stringResource(R.string.done), onClick = onDone)
    }
}

@Composable
private fun RemoveConfirmation(
    account: YouTubeAccount,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TvProfileAvatar(account, size = AvatarSize)
            Text(
                text = stringResource(R.string.profile_remove_confirm_title, account.name),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            text = stringResource(R.string.profile_remove_confirm_body, account.name),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(text = stringResource(R.string.cancel), onClick = onCancel)
            TvButton(text = stringResource(R.string.youtube_account_remove), icon = Icons.Outlined.PersonRemove, onClick = onRemove)
        }
    }
}

/** The account's picture, or a generic person for Guest and accounts without one. */
@Composable
fun TvProfileAvatar(
    account: YouTubeAccount?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        val url = account?.avatarUrl.orEmpty()
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

private val AvatarSize = 40.dp
