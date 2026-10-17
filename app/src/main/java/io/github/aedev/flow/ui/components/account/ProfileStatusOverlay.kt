package io.github.aedev.flow.ui.components.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.ProfileTransition
import io.github.aedev.flow.data.youtubeaccount.SyncProgress
import io.github.aedev.flow.ui.screens.settings.youtubeaccount.ProfileStatusViewModel

/**
 * Sits over the whole app, phone and TV. While Flow closes to open another profile it covers the
 * screen with who it is switching to; while an account's first import from YouTube runs it shows a
 * small status card with the step it is on; and right after a switch it briefly names the profile.
 */
@Composable
fun ProfileStatusOverlay(viewModel: ProfileStatusViewModel = hiltViewModel()) {
    val switching by viewModel.switching.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val arrival by viewModel.arrival.collectAsStateWithLifecycle()

    val transition = switching
    if (transition != null) {
        ProfileSwitchingScreen(transition)
        return
    }
    val importing = syncing?.takeIf { it.firstImport }
    val arrived = arrival
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        AnimatedVisibility(
            visible = importing != null || arrived != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            when {
                importing != null -> ImportCard(importing)
                arrived != null -> StatusCard(title = arrivalTitle(arrived), detail = null, busy = false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileSwitchingScreen(transition: ProfileTransition) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingIndicator(modifier = Modifier.size(72.dp))
            Text(
                text =
                    transition.name?.let { stringResource(R.string.profile_switching_to, it) }
                        ?: stringResource(R.string.profile_switching_to_guest),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.profile_switching_detail),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ImportCard(progress: SyncProgress) {
    val step =
        when (progress.phase) {
            SyncProgress.Phase.HISTORY -> R.string.profile_import_history
            SyncProgress.Phase.SUBSCRIPTIONS -> R.string.profile_import_subscriptions
            SyncProgress.Phase.LIBRARY -> R.string.profile_import_library
        }
    StatusCard(
        title = stringResource(R.string.profile_import_title, progress.accountName),
        detail = stringResource(step),
        busy = true,
    )
}

@Composable
private fun arrivalTitle(arrived: ProfileTransition): String =
    arrived.name?.let { stringResource(R.string.profile_now_watching_as, it) }
        ?: stringResource(R.string.profile_now_watching_as_guest)

@Composable
private fun StatusCard(
    title: String,
    detail: String?,
    busy: Boolean,
) {
    Surface(
        modifier = Modifier.widthIn(max = 520.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.inversePrimary,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(Icons.Outlined.AccountCircle, contentDescription = null)
            }
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                detail?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
