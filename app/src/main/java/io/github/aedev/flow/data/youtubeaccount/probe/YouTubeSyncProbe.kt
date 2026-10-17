package io.github.aedev.flow.data.youtubeaccount.probe

import android.util.Log
import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountApi
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The YouTube sync diagnostics: tries each write the two-way sync needs as the active account, reads
 * each result back, and undoes what it did. It runs in its own scope, not the screen's, so leaving the
 * page mid-run cannot skip the clean-up. Nothing runs until someone taps Run.
 */
@Singleton
class YouTubeSyncProbe
    @Inject
    constructor(
        private val accounts: YouTubeAccountManager,
        private val api: YouTubeAccountApi,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _state = MutableStateFlow(SyncProbeRun())
        val state: StateFlow<SyncProbeRun> = _state.asStateFlow()

        fun run() {
            if (_state.value.running) return
            _state.value = SyncProbeRun(running = true, results = SyncProbeCheck.entries.map { SyncProbeResult(it) })
            scope.launch {
                val account = accounts.state.first().active
                if (account == null || account.needsSignIn) {
                    _state.value = SyncProbeRun(error = SyncProbeNote(R.string.yt_probe_error_signed_out))
                    return@launch
                }
                _state.update { it.copy(accountName = account.name) }
                try {
                    SyncProbeSession(api, accounts, ::publish).runAll()
                } catch (e: Exception) {
                    Log.w(TAG, "Sync diagnostics stopped early", e)
                    _state.update { it.copy(error = SyncProbeNote(R.string.yt_probe_note_error, e.toString())) }
                } finally {
                    _state.update { run ->
                        run.copy(
                            running = false,
                            finishedAt = System.currentTimeMillis(),
                            results = run.results.map(::interrupted),
                        )
                    }
                }
            }
        }

        private fun publish(result: SyncProbeResult) {
            Log.i(
                TAG,
                "${result.check} ${result.status} http=${result.httpStatus} ${result.notes.joinToString { it.args.joinToString("/") }}",
            )
            _state.update { run -> run.copy(results = run.results.map { if (it.check == result.check) result else it }) }
        }

        /** A check still running when the run stopped did not finish. */
        private fun interrupted(result: SyncProbeResult) =
            if (result.status == SyncProbeStatus.RUNNING) result.copy(status = SyncProbeStatus.FAIL) else result

        private companion object {
            const val TAG = "YouTubeSyncProbe"
        }
    }
