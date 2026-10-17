package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.profile.ProfileSwitcher
import io.github.aedev.flow.data.profile.ProfileTransition
import io.github.aedev.flow.data.youtubeaccount.SyncProgress
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountStore
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the app shows over every screen while a profile opens or an account imports from YouTube. */
@HiltViewModel
class ProfileStatusViewModel
    @Inject
    constructor(
        switcher: ProfileSwitcher,
        sync: YouTubeAccountSync,
        private val accounts: YouTubeAccountStore,
    ) : ViewModel() {
        val switching: StateFlow<ProfileTransition?> = switcher.switching
        val syncing: StateFlow<SyncProgress?> = sync.progress

        private val _arrival = MutableStateFlow<ProfileTransition?>(null)

        /** The profile this start opened, shown briefly after a switch. */
        val arrival: StateFlow<ProfileTransition?> = _arrival.asStateFlow()

        init {
            switcher.takeArrival()?.let { arrived ->
                viewModelScope.launch {
                    val name =
                        accounts
                            .current()
                            .accounts
                            .firstOrNull { it.id == arrived.key }
                            ?.name
                    _arrival.value = arrived.copy(name = name)
                    delay(ARRIVAL_NOTE_MS)
                    _arrival.value = null
                }
            }
        }

        private companion object {
            const val ARRIVAL_NOTE_MS = 3_500L
        }
    }
