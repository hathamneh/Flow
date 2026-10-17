package io.github.aedev.flow.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

/** The clear-history question, with the choice to clear the signed-in account's YouTube history too. */
@Composable
internal fun ClearHistoryDialogBody(
    canClearOnYouTube: Boolean,
    alsoOnYouTube: Boolean,
    onAlsoOnYouTubeChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.clear_watch_history_alert_body))
        if (canClearOnYouTube) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = alsoOnYouTube, role = Role.Checkbox, onValueChange = onAlsoOnYouTubeChange),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = alsoOnYouTube, onCheckedChange = null)
                Column {
                    Text(stringResource(R.string.clear_history_also_youtube), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.clear_history_also_youtube_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
