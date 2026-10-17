package io.github.aedev.flow.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Whether the open playlist stays in Flow only while a YouTube account is signed in: one of the
 * viewer's own playlists that isn't one of the account's, so its edits aren't sent anywhere.
 */
@HiltViewModel
class PlaylistAccountLinkViewModel
    @Inject
    constructor(
        accounts: YouTubeAccountStore,
        playlists: PlaylistRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

        val flowOnly: StateFlow<Boolean> =
            combine(accounts.state.map { it.active != null }, playlists.observePlaylistEntity(playlistId)) { signedIn, playlist ->
                signedIn &&
                    playlist != null &&
                    playlist.isUserCreated &&
                    playlist.remoteId == null &&
                    playlist.id != PlaylistRepository.WATCH_LATER_ID &&
                    playlist.id != PlaylistRepository.SAVED_SHORTS_ID
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
