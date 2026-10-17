package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccount
import io.github.aedev.flow.data.youtubeaccount.YouTubeSignInProgress
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val QrSize = 160.dp

/**
 * The phone's sign-in: the code, plus shortcuts for signing in on this same phone — open the page in
 * the browser and copy the code to paste there.
 */
@Composable
internal fun YouTubeSignInDialog(
    progress: YouTubeSignInProgress,
    scope: CoroutineScope,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val waiting = progress as? YouTubeSignInProgress.AwaitingApproval
    val copied = stringResource(R.string.youtube_account_code_copied)
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.youtube_account_sign_in_title)) },
        text = { YouTubeSignInContent(progress, qrSize = QrSize, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            when {
                waiting != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, waiting.code.userCode)))
                                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) { Text(stringResource(R.string.youtube_account_copy_code)) }
                        TextButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waiting.code.verificationUrl)))
                                } catch (_: ActivityNotFoundException) {
                                    Unit
                                }
                            },
                        ) { Text(stringResource(R.string.youtube_account_open_page)) }
                    }
                }

                progress is YouTubeSignInProgress.Failed -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.youtube_account_try_again)) }
                }

                progress is YouTubeSignInProgress.Done -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
                }

                else -> {
                    Unit
                }
            }
        },
        dismissButton =
            if (progress is YouTubeSignInProgress.Done) {
                null
            } else {
                { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
            },
    )
}

/** What can be done with one signed-in account. */
@Composable
internal fun YouTubeAccountActionsDialog(
    account: YouTubeAccount,
    isActive: Boolean,
    onUse: () -> Unit,
    onSignInAgain: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(account.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account.subtitle.isNotBlank()) Text(account.subtitle)
                Text(stringResource(R.string.youtube_account_remove_summary))
            }
        },
        confirmButton = {
            when {
                account.needsSignIn -> TextButton(onClick = onSignInAgain) { Text(stringResource(R.string.youtube_account_needs_sign_in)) }
                !isActive -> TextButton(onClick = onUse) { Text(stringResource(R.string.youtube_account_use)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.youtube_account_remove)) } },
    )
}
