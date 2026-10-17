package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.github.aedev.flow.ui.components.settings.SettingsPage

private val ResultPadding = 16.dp
private val IntroGap = 12.dp

/** Debug and sync builds only: runs the YouTube write checks as [accountName] and shows each outcome. */
@Composable
internal fun YouTubeSyncDiagnosticsScreen(
    accountName: String,
    onBack: () -> Unit,
    viewModel: YouTubeSyncDiagnosticsViewModel = hiltViewModel(),
) {
    val run by viewModel.run.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    SettingsPage(title = stringResource(R.string.yt_probe_title), onBack = onBack) {
        item("yt_probe.intro") {
            Column(verticalArrangement = Arrangement.spacedBy(IntroGap)) {
                Text(
                    stringResource(R.string.yt_probe_intro, run.accountName ?: accountName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = viewModel::start, enabled = !run.running) {
                    Text(
                        stringResource(
                            when {
                                run.running -> R.string.yt_probe_running
                                run.finishedAt != null -> R.string.yt_probe_run_again
                                else -> R.string.yt_probe_run
                            },
                        ),
                    )
                }
                if (run.finishedAt != null) Text(syncProbeSummary(run), style = MaterialTheme.typography.titleSmall)
                run.error?.let { error ->
                    Text(
                        stringResource(error.text, *error.args.toTypedArray()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        group(key = "yt_probe.results") {
            run.results.forEach { result ->
                row("yt_probe.result.${result.check.name}") { shape ->
                    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        SyncProbeResultContent(result, Modifier.padding(ResultPadding))
                    }
                }
            }
        }
    }
}
