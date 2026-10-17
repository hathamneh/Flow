package io.github.aedev.flow.ui.screens.settings.youtubeaccount

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeRun
import io.github.aedev.flow.data.youtubeaccount.probe.YouTubeSyncProbe
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The sync diagnostics page on phone and TV; the run itself lives in [YouTubeSyncProbe], not here. */
@HiltViewModel
class YouTubeSyncDiagnosticsViewModel
    @Inject
    constructor(
        private val probe: YouTubeSyncProbe,
    ) : ViewModel() {
        val run: StateFlow<SyncProbeRun> = probe.state

        fun start() = probe.run()
    }
