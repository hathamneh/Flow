package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Sends the queued library changes ([YouTubeOutbox]) once there's a connection, backing off while
 * YouTube can't be reached. WorkManager is shared by every profile, so a run in a profile with no
 * account, or with nothing queued, ends at once.
 */
class YouTubeOutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun outbox(): YouTubeOutbox
    }

    override suspend fun doWork(): Result {
        val outbox = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).outbox()
        return when (outbox.drain()) {
            YouTubeOutbox.DrainResult.DONE -> Result.success()
            YouTubeOutbox.DrainResult.MORE -> Result.success().also { enqueue(applicationContext) }
            YouTubeOutbox.DrainResult.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "flow_youtube_outbox"
        private const val BACKOFF_SECONDS = 30L

        /** Queues a round of sending; [delaySeconds] keeps it off a starting app's main thread. */
        fun enqueue(
            context: Context,
            delaySeconds: Long = 0,
        ) {
            val work =
                OneTimeWorkRequestBuilder<YouTubeOutboxWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}
