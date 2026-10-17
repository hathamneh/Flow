package io.github.aedev.flow.data.profile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One profile per Google account, plus Guest. Each has its own history, subscriptions, likes,
 * playlists and recommendations; switching parks the current profile's data and restarts Flow
 * into the other's, so no screen, cache or engine can carry anything across.
 */
@Singleton
class ProfileSwitcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val accounts: YouTubeAccountStore,
    ) {
        private val files by lazy { ProfileFiles(context.dataDir) }

        /**
         * Lines the account list up with the data in place, once per start. The first time profiles
         * are used, the data already here becomes the active account's (or Guest's). Parked data of
         * accounts that were removed is deleted.
         */
        suspend fun reconcile() =
            withContext(Dispatchers.IO) {
                val state = accounts.current()
                val inPlace = files.current()
                if (inPlace == null) {
                    files.setCurrent(ProfileFiles.keyFor(state.activeId))
                } else if (inPlace != ProfileFiles.keyFor(state.activeId)) {
                    val owner = state.accounts.firstOrNull { it.id == inPlace }
                    // A switch that never restarted, or a removed account: follow the data in place.
                    if (owner != null || inPlace == ProfileFiles.GUEST) {
                        accounts.setActive(owner?.id)
                    } else {
                        accounts.setActive(null)
                        restartInto(ProfileFiles.GUEST)
                    }
                }
                val known = state.accounts.map { it.id }.toSet() + ProfileFiles.GUEST
                files.parkedKeys().filterNot { it in known }.forEach(files::deleteParked)
            }

        /** Makes [accountId] (null for Guest) active, restarting into its data when that is another profile. */
        suspend fun switchTo(accountId: String?) {
            val target = ProfileFiles.keyFor(accountId)
            accounts.setActive(accountId)
            if (withContext(Dispatchers.IO) { files.current() } != target) restartInto(target)
        }

        /**
         * A sign-in finished for [accountId], which the store has made active. A first account signed in
         * from Guest takes Guest's data with it, as signing in used to; any other opens its own profile.
         */
        suspend fun onSignedIn(accountId: String) {
            val inPlace = withContext(Dispatchers.IO) { files.current() }
            if (inPlace == accountId) return
            val hadOtherAccounts = accounts.current().accounts.any { it.id != accountId }
            if (!hadOtherAccounts && (inPlace == null || inPlace == ProfileFiles.GUEST)) {
                withContext(Dispatchers.IO) { files.setCurrent(accountId) }
                return
            }
            restartInto(accountId)
        }

        /** The account [accountId] was removed: its profile goes with it, and Guest takes over if it was in place. */
        suspend fun onRemoved(accountId: String) {
            val inPlace = withContext(Dispatchers.IO) { files.current() }
            if (inPlace == accountId) {
                // Parked on the way out, then deleted by reconcile() on the next start.
                restartInto(ProfileFiles.GUEST)
            } else {
                withContext(Dispatchers.IO) { files.deleteParked(accountId) }
            }
        }

        private suspend fun restartInto(key: String) {
            withContext(Dispatchers.IO) { files.requestSwitch(key) }
            Log.i(TAG, "Restarting into profile $key")
            restart()
        }

        /** Relaunches Flow in a fresh process, which applies the switch before opening any data. */
        private fun restart() {
            context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java)))
            Runtime.getRuntime().exit(0)
        }

        companion object {
            private const val TAG = "ProfileSwitcher"

            /** Puts a requested profile in place. Runs first thing at process start. */
            fun applyPendingSwitch(context: Context) {
                runCatching { ProfileFiles(context.dataDir).applyPending() }
                    .onFailure { Log.e(TAG, "Profile switch failed", it) }
            }
        }
    }
