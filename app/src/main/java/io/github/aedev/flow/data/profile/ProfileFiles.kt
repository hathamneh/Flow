package io.github.aedev.flow.data.profile

import android.util.Log
import java.io.File

/**
 * Keeps each profile's data apart on disk. The active profile's files sit where the app expects them;
 * every other profile's are parked under `no_backup/profiles/<key>/`, mirroring their paths. A switch is
 * only ever applied at process start, before anything opens a database or a store: the files of the
 * profile in place are parked, then the target's are moved in.
 *
 * What belongs to a profile is everything personal: the database (history, playlists, feeds, downloads
 * list), the recommendation engines, likes, subscriptions, searches, queues. Device settings — player,
 * theme, fonts, language, the account list itself — stay shared by every profile.
 *
 * Each step is safe to repeat, so a switch cut short by a crash finishes on the next start.
 */
class ProfileFiles(
    private val dataDir: File,
) {
    private val root = File(dataDir, "no_backup/profiles")
    private val currentFile = File(root, "current")
    private val pendingFile = File(root, "pending")

    /** The profile whose files are in place, or null before profiles were first used on this install. */
    fun current(): String? = currentFile.readKey()

    fun pending(): String? = pendingFile.readKey()

    /** Claims the files in place for [key] without moving anything: the first run, or adopting guest data. */
    fun setCurrent(key: String) = currentFile.writeKey(key)

    /** Asks for [key] to be put in place on the next process start. */
    fun requestSwitch(key: String) = pendingFile.writeKey(key)

    /** Applies a requested switch. Call once, at process start, before any data is opened. */
    fun applyPending(): Boolean {
        val target = pending() ?: return false
        val current = current()
        if (current != null && current != target) {
            liveFiles().forEach { move(it, parked(current, it)) }
            setCurrent(target)
        } else if (current == null) {
            setCurrent(target)
        }
        // Restoring after the current key already names the target makes a retry pick up where it stopped.
        val parkedDir = File(root, target)
        if (parkedDir.isDirectory) {
            parkedDir
                .walkTopDown()
                .filter { it.isFile }
                .forEach { file -> move(file, File(dataDir, file.relativeTo(parkedDir).path)) }
            parkedDir.deleteRecursively()
        }
        pendingFile.delete()
        return true
    }

    /** Profiles with data parked aside, not counting the one in place. */
    fun parkedKeys(): List<String> =
        root
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()

    /** Forgets a parked profile's data. The profile in place is never deleted this way. */
    fun deleteParked(key: String) {
        if (key == current() || key.isBlank() || key.contains('/') || key.startsWith('.')) return
        File(root, key).deleteRecursively()
    }

    /** The files of the profile in place, by the rules in [isProfileOwned]. */
    internal fun liveFiles(): List<File> =
        LIVE_DIRS.flatMap { dir ->
            File(dataDir, dir)
                .listFiles()
                ?.filter { it.isFile && isProfileOwned(dir, it.name) }
                .orEmpty()
        }

    private fun parked(
        key: String,
        live: File,
    ) = File(File(root, key), live.relativeTo(dataDir).path)

    private fun move(
        from: File,
        to: File,
    ) {
        to.parentFile?.mkdirs()
        if (to.exists()) to.delete()
        if (from.renameTo(to)) return
        runCatching {
            from.copyTo(to, overwrite = true)
            from.delete()
        }.onFailure { Log.e(TAG, "Could not move ${from.name}", it) }
    }

    private fun File.readKey(): String? = takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun File.writeKey(key: String) {
        parentFile?.mkdirs()
        val temp = File(parentFile, "$name.tmp")
        temp.writeText(key)
        if (!temp.renameTo(this)) {
            writeText(key)
            temp.delete()
        }
    }

    companion object {
        private const val TAG = "ProfileFiles"

        /** The key of the profile with no Google account. */
        const val GUEST = "guest"

        private const val DATABASES = "databases"
        private const val DATASTORE = "files/datastore"
        private const val FILES = "files"
        private const val PREFERENCE_DIR = "shared_prefs"
        private val LIVE_DIRS = listOf(DATABASES, DATASTORE, FILES, PREFERENCE_DIR)

        private const val DATABASE = "flow_database"

        /** Engine data from before it moved into the datastore folder. */
        private const val LEGACY_BRAIN = "user_neuro_brain.json"

        /** Device settings and device state every profile shares. Matched against the store's name. */
        private val SHARED_STORES =
            setOf(
                "player_preferences",
                "flow_preferences",
                "app_font",
                "app_ui_mode",
                "audio_settings",
                "local_media",
                "youtube_accounts",
                "sync_identity",
                "widget_theme",
                "widget_content",
                "now_playing_widget",
                "animated_artwork",
            )

        /** Personal shared-preference files: the music home's picks and YouTube's visitor identity. */
        private val PROFILE_PREFS = setOf("music_home_cache_prefs.xml", "youtube_identity.xml")

        fun keyFor(accountId: String?): String = accountId ?: GUEST

        internal fun isProfileOwned(
            dir: String,
            name: String,
        ): Boolean =
            when (dir) {
                DATABASES -> {
                    name == DATABASE || name.startsWith("$DATABASE-")
                }

                DATASTORE -> {
                    val store = name.substringBefore(".preferences_pb").removeSuffix(".json")
                    store !in SHARED_STORES && !store.startsWith("GlanceAppWidgetManager")
                }

                FILES -> {
                    name == LEGACY_BRAIN
                }

                PREFERENCE_DIR -> {
                    name in PROFILE_PREFS
                }

                else -> {
                    false
                }
            }
    }
}
