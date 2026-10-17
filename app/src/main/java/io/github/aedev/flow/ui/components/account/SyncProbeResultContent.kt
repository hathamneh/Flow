package io.github.aedev.flow.ui.components.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeResult
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeRun
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeStatus

private val StatusIconSize = 24.dp
private val ContentGap = 16.dp
private val LineGap = 2.dp

/** One check of the sync diagnostics: its name, the outcome with the HTTP status, and the read-back notes. */
@Composable
internal fun SyncProbeResultContent(
    result: SyncProbeResult,
    modifier: Modifier = Modifier,
) {
    val tint = result.status.tint()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(ContentGap)) {
        Icon(result.status.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(StatusIconSize))
        Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
            Text(stringResource(result.check.title), style = MaterialTheme.typography.bodyLarge)
            val outcome =
                listOfNotNull(
                    stringResource(result.status.label()),
                    result.httpStatus?.let { stringResource(R.string.yt_probe_http, it) },
                ).joinToString(" · ")
            Text(outcome, style = MaterialTheme.typography.labelLarge, color = tint)
            result.notes.forEach { note ->
                Text(
                    stringResource(note.text, *note.args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "N passed · N failed · N other" for a finished run. */
@Composable
internal fun syncProbeSummary(run: SyncProbeRun): String {
    val passed = run.results.count { it.status == SyncProbeStatus.PASS }
    val failed = run.results.count { it.status == SyncProbeStatus.FAIL }
    return stringResource(R.string.yt_probe_summary, passed, failed, run.results.size - passed - failed)
}

@Composable
private fun SyncProbeStatus.tint(): Color =
    when (this) {
        SyncProbeStatus.PASS -> MaterialTheme.colorScheme.primary
        SyncProbeStatus.FAIL -> MaterialTheme.colorScheme.error
        SyncProbeStatus.NOT_REFLECTED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun SyncProbeStatus.icon(): ImageVector =
    when (this) {
        SyncProbeStatus.PENDING -> Icons.Outlined.HourglassEmpty
        SyncProbeStatus.RUNNING -> Icons.Outlined.Sync
        SyncProbeStatus.PASS -> Icons.Outlined.CheckCircle
        SyncProbeStatus.FAIL -> Icons.Outlined.ErrorOutline
        SyncProbeStatus.NOT_REFLECTED -> Icons.Outlined.WarningAmber
        SyncProbeStatus.UNVERIFIED -> Icons.AutoMirrored.Outlined.HelpOutline
        SyncProbeStatus.SKIPPED -> Icons.Outlined.RemoveCircleOutline
        SyncProbeStatus.MANUAL -> Icons.Outlined.Visibility
    }

private fun SyncProbeStatus.label(): Int =
    when (this) {
        SyncProbeStatus.PENDING -> R.string.yt_probe_status_pending
        SyncProbeStatus.RUNNING -> R.string.yt_probe_status_running
        SyncProbeStatus.PASS -> R.string.yt_probe_status_pass
        SyncProbeStatus.FAIL -> R.string.yt_probe_status_fail
        SyncProbeStatus.NOT_REFLECTED -> R.string.yt_probe_status_not_reflected
        SyncProbeStatus.UNVERIFIED -> R.string.yt_probe_status_unverified
        SyncProbeStatus.SKIPPED -> R.string.yt_probe_status_skipped
        SyncProbeStatus.MANUAL -> R.string.yt_probe_status_manual
    }
