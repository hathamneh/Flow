package io.github.aedev.flow.ui.tv.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.account.SyncProbeResultContent
import io.github.aedev.flow.ui.components.account.syncProbeSummary
import io.github.aedev.flow.ui.screens.settings.youtubeaccount.YouTubeSyncDiagnosticsViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvCard

private val ResultPadding = 16.dp

/** The sync diagnostics on TV: the phone page's run and results, one focusable card per check for the remote. */
@Composable
internal fun TvSyncDiagnosticsPanel(
    accountName: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: YouTubeSyncDiagnosticsViewModel = hiltViewModel(),
) {
    val run by viewModel.run.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.yt_probe_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.yt_probe_intro, run.accountName ?: accountName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text =
                    stringResource(
                        when {
                            run.running -> R.string.yt_probe_running
                            run.finishedAt != null -> R.string.yt_probe_run_again
                            else -> R.string.yt_probe_run
                        },
                    ),
                onClick = { if (!run.running) viewModel.start() },
            )
            TvButton(text = stringResource(R.string.done), onClick = onClose)
        }
        if (run.finishedAt != null) Text(syncProbeSummary(run), style = MaterialTheme.typography.titleMedium)
        run.error?.let { error ->
            Text(
                stringResource(error.text, *error.args.toTypedArray()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        run.results.forEach { result ->
            TvCard(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                SyncProbeResultContent(result, Modifier.padding(ResultPadding))
            }
        }
    }
}
