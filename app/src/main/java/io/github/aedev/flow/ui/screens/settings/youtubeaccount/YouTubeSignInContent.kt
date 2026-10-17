package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.YouTubeSignInProgress
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.screens.sync.QrCodeImage

private val ContentSpacing = 12.dp
private val LoadingSize = 48.dp

/**
 * The body of a sign-in, whatever is showing it: the code and where to enter it, a QR code for that
 * page, then the result. Buttons are left to the caller because a phone and a TV offer different ones.
 */
@Composable
fun YouTubeSignInContent(
    progress: YouTubeSignInProgress,
    qrSize: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ContentSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (progress) {
            YouTubeSignInProgress.Starting -> {
                FlowLoadingIndicator(Modifier.size(LoadingSize))
                Text(stringResource(R.string.youtube_account_sign_in_starting), style = MaterialTheme.typography.bodyMedium)
            }

            is YouTubeSignInProgress.AwaitingApproval -> {
                val code = progress.code
                Text(
                    text = stringResource(R.string.youtube_account_sign_in_steps, code.verificationUrl.displayHost()),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = code.userCode,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                QrCodeImage(text = code.verificationUrl, modifier = Modifier.size(qrSize))
                Text(
                    text = stringResource(R.string.youtube_account_sign_in_scan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.youtube_account_sign_in_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is YouTubeSignInProgress.Done -> {
                Text(
                    text = stringResource(R.string.youtube_account_signed_in, progress.account.name),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            is YouTubeSignInProgress.Failed -> {
                Text(
                    text = stringResource(progress.reason.messageRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@StringRes
private fun YouTubeSignInProgress.Reason.messageRes(): Int =
    when (this) {
        YouTubeSignInProgress.Reason.NETWORK -> R.string.youtube_account_failed_network
        YouTubeSignInProgress.Reason.DENIED -> R.string.youtube_account_failed_denied
        YouTubeSignInProgress.Reason.EXPIRED -> R.string.youtube_account_failed_expired
        YouTubeSignInProgress.Reason.NO_CLIENT -> R.string.youtube_account_failed_no_client
        YouTubeSignInProgress.Reason.NO_ACCOUNT -> R.string.youtube_account_failed_no_account
    }

/** "https://www.google.com/device" → "google.com/device", as people type it. */
internal fun String.displayHost(): String = removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
