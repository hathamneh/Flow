package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs [YouTubeAccountSync] when Flow comes to the foreground and every few hours in the background,
 * so a device picks up what was watched elsewhere without anyone opening Settings. A run in guest mode,
 * or soon after the last one, ends at once.
 */
class YouTubeAccountSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun sync(): YouTubeAccountSync
    }

    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).sync()
        return try {
            sync.syncIfStale()
            Result.success()
        } catch (_: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val ONE_TIME_NAME = "flow_youtube_account_sync"
        private const val PERIODIC_NAME = "flow_youtube_account_sync_periodic"
        private const val PERIOD_HOURS = 3L
        private const val BACKOFF_MINUTES = 5L
        private const val MAX_ATTEMPTS = 3

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private const val START_DELAY_S = 10L

        @Volatile
        private var requested = false

        /**
         * Called when Flow is opened; the sync itself skips the run if it synced moments ago. Once per
         * process, and delayed: a job bound while the app is still starting competes with startup for the
         * main thread, and on a slow TV that tipped launches into not-responding kills.
         */
        fun requestSync(context: Context) {
            if (requested) return
            requested = true
            val work =
                OneTimeWorkRequestBuilder<YouTubeAccountSyncWorker>()
                    .setConstraints(connected)
                    .setInitialDelay(START_DELAY_S, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                    .build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.REPLACE, work)
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<YouTubeAccountSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(connected)
                    .setInitialDelay(PERIOD_HOURS, TimeUnit.HOURS)
                    .build(),
            )
        }
    }
}
