package io.github.aedev.flow.data.youtubeaccount

import kotlinx.serialization.Serializable

/**
 * A Google account signed in through the TV device-code flow. Several can be kept on one device and
 * one of them (or none, for guest mode) is active at a time. The refresh token is stored separately,
 * sealed with the Keystore — it never sits in this model.
 */
@Serializable
data class YouTubeAccount(
    val id: String,
    val name: String,
    val email: String = "",
    val handle: String = "",
    val avatarUrl: String = "",
    /** Set for brand accounts: sent as `X-Goog-Pageid` so requests act as that channel. */
    val pageId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Google refused the stored refresh token (revoked or expired); the account must sign in again. */
    val needsSignIn: Boolean = false,
) {
    /** The line shown under the name: email when known, else the channel handle. */
    val subtitle: String
        get() = email.ifBlank { handle }
}

data class YouTubeAccountsState(
    val accounts: List<YouTubeAccount> = emptyList(),
    val activeId: String? = null,
    /** Send watched videos to the active account's YouTube history. */
    val reportHistory: Boolean = true,
) {
    val active: YouTubeAccount?
        get() = accounts.firstOrNull { it.id == activeId }
}

/** The OAuth client the YouTube TV app signs in with, read from its own web bundle. */
data class TvOAuthClient(
    val clientId: String,
    val clientSecret: String,
)

/** What a pending device-code sign-in shows: the code to type and where to type it. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMs: Long,
    val intervalSeconds: Int,
)

/** The tokens a finished sign-in or a refresh returns. */
data class OAuthTokens(
    val accessToken: String,
    val expiresAtMs: Long,
    val refreshToken: String?,
)

sealed interface TokenPoll {
    data object Pending : TokenPoll

    data object SlowDown : TokenPoll

    data object Denied : TokenPoll

    data object Expired : TokenPoll

    data class Granted(
        val tokens: OAuthTokens,
    ) : TokenPoll
}

/** One identity in `accounts_list`: the Google account itself or one of its brand channels. */
data class RemoteIdentity(
    val name: String,
    val email: String,
    val handle: String,
    val avatarUrl: String,
    val pageId: String?,
    val isSelected: Boolean,
)

/** The two playback-stats URLs a signed-in `/player` response carries for a video. */
data class PlaybackTrackingUrls(
    val playbackUrl: String,
    val watchtimeUrl: String?,
    /** The video's length from the same response; null when the player left it out. */
    val lengthSeconds: Long? = null,
)

data class RemoteChannel(
    val channelId: String,
    val name: String,
    val thumbnailUrl: String,
)

data class RemoteHistoryItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val durationMs: Long,
    /** 0–100, from the resume bar on the history tile; 0 when YouTube shows none. */
    val percentWatched: Int,
    /** YouTube marks songs and music videos; liked music and music playlists are made of these. */
    val isMusic: Boolean = false,
)

/** One of the account's own or saved playlists, from the TV library page. */
data class RemotePlaylist(
    val playlistId: String,
    val title: String,
    val thumbnailUrl: String,
)

/** Progress of an interactive sign-in, in the order it moves through them. */
sealed interface YouTubeSignInProgress {
    data object Starting : YouTubeSignInProgress

    data class AwaitingApproval(
        val code: DeviceCode,
    ) : YouTubeSignInProgress

    data class Done(
        val account: YouTubeAccount,
    ) : YouTubeSignInProgress

    data class Failed(
        val reason: Reason,
    ) : YouTubeSignInProgress

    enum class Reason { NETWORK, DENIED, EXPIRED, NO_CLIENT, NO_ACCOUNT }
}

/** Raised when a refresh token is no longer accepted, so the account is flagged for a new sign-in. */
class YouTubeAuthRevokedException : Exception("Google no longer accepts this account's sign-in")

/** What a running sync is doing, for the status shown over the app. */
data class SyncProgress(
    val accountName: String,
    val phase: Phase,
    /** The profile's first sync: its whole import from YouTube. */
    val firstImport: Boolean,
) {
    enum class Phase { HISTORY, SUBSCRIPTIONS, LIBRARY }
}

/** One bell choice for a channel: its raw state name, the params that select it, and whether it is current. */
data class BellOption(
    val state: String,
    val params: String,
    val selected: Boolean,
)

/** One entry of a playlist; [setVideoId] tells duplicate entries of a video apart. */
data class PlaylistEntry(
    val videoId: String,
    val setVideoId: String?,
)
