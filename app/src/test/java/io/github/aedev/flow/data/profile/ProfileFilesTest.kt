package io.github.aedev.flow.data.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(
        path: String,
        text: String,
    ) = File(temp.root, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun read(path: String): String? = File(temp.root, path).takeIf { it.isFile }?.readText()

    private fun seedAlice() {
        write("databases/flow_database", "alice-db")
        write("databases/flow_database-wal", "alice-wal")
        write("files/datastore/liked_videos.preferences_pb", "alice-likes")
        write("files/datastore/flow_neuro_brain_v10.json", "alice-brain")
        write("shared_prefs/youtube_identity.xml", "alice-visitor")
        write("files/datastore/player_preferences.preferences_pb", "settings")
        write("files/datastore/youtube_accounts.preferences_pb", "accounts")
        write("databases/exoplayer_internal.db", "player-cache")
        write("shared_prefs/flow_language_prefs.xml", "language")
    }

    @Test
    fun `switching parks personal data, leaves settings, and starts the new profile empty`() {
        seedAlice()
        val files = ProfileFiles(temp.root)
        files.setCurrent("alice")

        files.requestSwitch("bob")
        assertThat(files.applyPending()).isTrue()

        assertThat(files.current()).isEqualTo("bob")
        assertThat(files.pending()).isNull()
        assertThat(read("databases/flow_database")).isNull()
        assertThat(read("files/datastore/liked_videos.preferences_pb")).isNull()
        assertThat(read("shared_prefs/youtube_identity.xml")).isNull()
        assertThat(read("files/datastore/player_preferences.preferences_pb")).isEqualTo("settings")
        assertThat(read("files/datastore/youtube_accounts.preferences_pb")).isEqualTo("accounts")
        assertThat(read("databases/exoplayer_internal.db")).isEqualTo("player-cache")
        assertThat(read("shared_prefs/flow_language_prefs.xml")).isEqualTo("language")
        assertThat(files.parkedKeys()).containsExactly("alice")
    }

    @Test
    fun `switching back restores each profile's own data`() {
        seedAlice()
        val files = ProfileFiles(temp.root)
        files.setCurrent("alice")
        files.requestSwitch("bob")
        files.applyPending()
        write("databases/flow_database", "bob-db")

        files.requestSwitch("alice")
        files.applyPending()

        assertThat(read("databases/flow_database")).isEqualTo("alice-db")
        assertThat(read("databases/flow_database-wal")).isEqualTo("alice-wal")
        assertThat(read("files/datastore/flow_neuro_brain_v10.json")).isEqualTo("alice-brain")
        assertThat(read("no_backup/profiles/bob/databases/flow_database")).isEqualTo("bob-db")
        assertThat(files.parkedKeys()).containsExactly("bob")
    }

    @Test
    fun `a switch cut short after parking finishes on the next start`() {
        seedAlice()
        val files = ProfileFiles(temp.root)
        files.setCurrent("bob")
        write("no_backup/profiles/alice/databases/flow_database", "alice-db")
        write("no_backup/profiles/alice/files/datastore/liked_videos.preferences_pb", "alice-likes")
        // Bob's data was parked and the key moved to Alice, then the process died mid-restore.
        files.setCurrent("alice")
        File(temp.root, "databases/flow_database").delete()
        File(temp.root, "files/datastore/liked_videos.preferences_pb").delete()
        files.requestSwitch("alice")

        files.applyPending()

        assertThat(read("databases/flow_database")).isEqualTo("alice-db")
        assertThat(read("files/datastore/liked_videos.preferences_pb")).isEqualTo("alice-likes")
        assertThat(files.parkedKeys()).isEmpty()
    }

    @Test
    fun `nothing moves without a pending switch, and the profile in place is never deleted`() {
        seedAlice()
        val files = ProfileFiles(temp.root)
        files.setCurrent("alice")

        assertThat(files.applyPending()).isFalse()
        files.deleteParked("alice")

        assertThat(read("databases/flow_database")).isEqualTo("alice-db")
    }

    @Test
    fun `widget and settings stores are shared, everything else in the datastore folder is personal`() {
        assertThat(ProfileFiles.isProfileOwned("files/datastore", "GlanceAppWidgetManager-io.github.aedev.flow.preferences_pb")).isFalse()
        assertThat(ProfileFiles.isProfileOwned("files/datastore", "flow_preferences.preferences_pb")).isFalse()
        assertThat(ProfileFiles.isProfileOwned("files/datastore", "subscriptions.preferences_pb")).isTrue()
        assertThat(ProfileFiles.isProfileOwned("files/datastore", "flow_music_brain_v1.json")).isTrue()
        assertThat(ProfileFiles.isProfileOwned("databases", "flow_database-shm")).isTrue()
        assertThat(ProfileFiles.isProfileOwned("files", "flow_crashes.log")).isFalse()
    }
}
