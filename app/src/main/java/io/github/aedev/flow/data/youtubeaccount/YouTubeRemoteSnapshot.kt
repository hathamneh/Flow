package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.youTubeSyncStateDataStore by safePreferencesDataStore(name = "youtube_sync_state")

/** A YouTube list whose last-seen contents the pull compares against. */
enum class RemoteCollection(
    val id: String,
) {
    SUBSCRIPTIONS("subs"),
    LIKES("likes"),
    MUSIC_LIKES("music_likes"),
    WATCH_LATER("watch_later"),
}

/** What a list looked like on YouTube at the last pull, in YouTube's order. */
data class Snapshot(
    val ids: List<String>,
    val takenAt: Long,
)

/**
 * What each of the account's lists looked like on YouTube when last pulled, so a pull can tell an
 * addition or removal made on YouTube from one made in Flow. Also counts the changes YouTube refused.
 * Profile-owned, like every store not listed as shared.
 */
@Singleton
class YouTubeRemoteSnapshot
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val store get() = context.youTubeSyncStateDataStore

        private fun idsKey(
            accountId: String,
            collection: RemoteCollection,
        ) = stringPreferencesKey("ids_${accountId}_${collection.id}")

        private fun takenAtKey(
            accountId: String,
            collection: RemoteCollection,
        ) = longPreferencesKey("taken_${accountId}_${collection.id}")

        private fun rejectedKey(accountId: String) = intPreferencesKey("rejected_$accountId")

        suspend fun get(
            accountId: String,
            collection: RemoteCollection,
        ): Snapshot? = store.data.first().snapshot(accountId, collection)

        suspend fun put(
            accountId: String,
            collection: RemoteCollection,
            ids: List<String>,
        ) {
            store.edit {
                it[idsKey(accountId, collection)] = ids.joinToString(SEPARATOR)
                it[takenAtKey(accountId, collection)] = System.currentTimeMillis()
            }
        }

        /** Forgets [id] in [collections] after Flow removed it on YouTube, so the next pull doesn't read it as YouTube's removal. */
        suspend fun forget(
            accountId: String,
            id: String,
            vararg collections: RemoteCollection,
        ) {
            store.edit { preferences ->
                collections.forEach { collection ->
                    val snapshot = preferences.snapshot(accountId, collection) ?: return@forEach
                    if (id in snapshot.ids) preferences[idsKey(accountId, collection)] = (snapshot.ids - id).joinToString(SEPARATOR)
                }
            }
        }

        fun rejectedCount(accountId: String): Flow<Int> = store.data.map { it[rejectedKey(accountId)] ?: 0 }

        suspend fun addRejected(
            accountId: String,
            count: Int,
        ) {
            if (count > 0) store.edit { it[rejectedKey(accountId)] = (it[rejectedKey(accountId)] ?: 0) + count }
        }

        suspend fun clearRejected(accountId: String) {
            store.edit { it.remove(rejectedKey(accountId)) }
        }

        private fun Preferences.snapshot(
            accountId: String,
            collection: RemoteCollection,
        ): Snapshot? {
            val ids = this[idsKey(accountId, collection)] ?: return null
            return Snapshot(ids.split(SEPARATOR).filter(String::isNotEmpty), this[takenAtKey(accountId, collection)] ?: 0L)
        }

        private companion object {
            // Video and channel ids never hold a comma.
            const val SEPARATOR = ","
        }
    }

/** What changed on YouTube since the last pull, and what the list looks like now as far as Flow can tell. */
data class RemoteDiff(
    val added: List<String>,
    val removed: List<String>,
    val snapshot: List<String>,
)

/**
 * Compares a fresh read of a YouTube list with what it looked like last time.
 *
 * - Anything Flow still has queued for an id ([pending]) is left alone either way: Flow's change is
 *   newer than what was read.
 * - With no earlier snapshot, everything read counts as added and nothing as removed. That is the
 *   first pull after sign-in or after this update.
 * - A walk that stopped at its page cap only saw the start of the list. An id missing from it counts
 *   as removed only if it would have shown up in what was read, when [positional] is true; YouTube
 *   puts new likes first and new Watch later entries last, so an id that is still there sits no later
 *   than its old place plus the number of new ids. A few places at the end of what was read are
 *   left for a longer walk, since moving one entry to the front shifts the rest by one. Without
 *   [positional], as for the channel list, removals need a [complete] walk.
 * - An empty read removes nothing. A list that YouTube empties is rare, and a page Flow can no
 *   longer parse looks exactly the same.
 *
 * The new snapshot is what was read, followed by older ids that couldn't be checked this time.
 */
fun remoteDiff(
    previous: List<String>?,
    fresh: List<String>,
    complete: Boolean,
    pending: Set<String>,
    positional: Boolean,
): RemoteDiff {
    val freshIds = fresh.toHashSet()
    if (fresh.isEmpty() && previous != null) return RemoteDiff(added = emptyList(), removed = emptyList(), snapshot = previous)
    if (previous == null) return RemoteDiff(added = fresh.filterNot { it in pending }, removed = emptyList(), snapshot = fresh)
    val known = previous.toHashSet()
    val newIds = fresh.count { it !in known }
    val added = fresh.filter { it !in known && it !in pending }
    val removed =
        previous.filterIndexed { index, id ->
            id !in freshIds && id !in pending && (complete || (positional && index + newIds < fresh.size - POSITION_MARGIN))
        }
    val gone = removed.toHashSet()
    val unchecked = previous.filter { it !in freshIds && it !in gone && !complete }
    return RemoteDiff(added = added, removed = removed, snapshot = fresh + unchecked)
}

/** How many places at the end of a partial read are too close to call. */
private const val POSITION_MARGIN = 3
