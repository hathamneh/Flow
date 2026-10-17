package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountManager
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountSync
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountsState
import io.github.aedev.flow.data.youtubeaccount.YouTubeSignInProgress
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Google account page, on the phone and on TV: the signed-in accounts and guest mode, adding an
 * account through a sign-in code, and syncing on demand.
 */
@HiltViewModel
class YouTubeAccountViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val manager: YouTubeAccountManager,
        private val sync: YouTubeAccountSync,
    ) : SettingsViewModel() {
        val state: StateFlow<YouTubeAccountsState> = manager.state.asState(YouTubeAccountsState())

        private val _signIn = MutableStateFlow<YouTubeSignInProgress?>(null)

        /** The sign-in in progress, or null when none is open. */
        val signIn: StateFlow<YouTubeSignInProgress?> = _signIn.asStateFlow()
        private var signInJob: Job? = null

        private val _syncing = MutableStateFlow(false)
        val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

        private val _messages = Channel<String>(Channel.BUFFERED)

        /** Results of syncs and sign-ins, for the page's snackbar. */
        val messages: Flow<String> = _messages.receiveAsFlow()

        fun startSignIn() {
            signInJob?.cancel()
            signInJob =
                viewModelScope.launch {
                    manager.signIn().collect { progress ->
                        _signIn.value = progress
                        if (progress is YouTubeSignInProgress.Done) {
                            _messages.send(context.getString(R.string.youtube_account_signed_in, progress.account.name))
                            syncNow()
                        }
                    }
                }
        }

        /** Closes the sign-in, abandoning it if it has not finished. */
        fun dismissSignIn() {
            signInJob?.cancel()
            signInJob = null
            _signIn.value = null
        }

        fun switchTo(id: String?) =
            write {
                manager.switchTo(id)
                if (id != null) syncNow()
            }

        fun remove(id: String) = write { manager.remove(id) }

        fun setReportHistory(enabled: Boolean) = write { manager.setReportHistory(enabled) }

        fun syncNow() {
            if (_syncing.value) return
            _syncing.value = true
            viewModelScope.launch {
                val message =
                    try {
                        val result = sync.syncNow()
                        when {
                            result == null -> {
                                null
                            }

                            result.isEmpty -> {
                                context.getString(R.string.youtube_account_sync_nothing)
                            }

                            else -> {
                                context.getString(
                                    R.string.youtube_account_sync_done,
                                    result.videos,
                                    result.channels,
                                    result.likes,
                                    result.playlists,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        context.getString(R.string.youtube_account_sync_failed)
                    } finally {
                        _syncing.value = false
                    }
                message?.let { _messages.send(it) }
            }
        }
    }
