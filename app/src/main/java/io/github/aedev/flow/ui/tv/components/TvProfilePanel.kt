package io.github.aedev.flow.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
            Text(
                text = stringResource(R.string.profile_switch_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
