package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.LibraryChange
import org.junit.Test

/** The pure rules behind sending history to YouTube and reading it back. */
class HistorySyncRulesTest {
    @Test
    fun `a watch record and a removal of one video share a key, so the newest wins`() {
        val watched = YouTubeMutation.from(LibraryChange.HistoryWatched("vid", 5_000, 10_000)).single()
        val removed = YouTubeMutation.from(LibraryChange.HistoryRemoved("vid")).single()

        assertThat(watched.key).isEqualTo(removed.key)
        assertThat(watched.key).isEqualTo("hist:vid")
        assertThat(watched.key).isNotEqualTo(YouTubeMutation.Rate("vid", io.github.aedev.flow.data.local.LibraryRating.LIKED).key)
    }

    @Test
    fun `clearing sends only when the viewer asked, and an undo sends nothing`() {
        assertThat(YouTubeMutation.from(LibraryChange.HistoryCleared(alsoOnYouTube = true))).containsExactly(YouTubeMutation.ClearHistory)
        assertThat(YouTubeMutation.from(LibraryChange.HistoryCleared(alsoOnYouTube = false))).isEmpty()
        assertThat(YouTubeMutation.from(LibraryChange.HistoryRestored("vid"))).isEmpty()
    }

    @Test
    fun `pause and resume coalesce to the last choice`() {
        val pause = YouTubeMutation.from(LibraryChange.HistoryPaused(true)).single()
        val resume = YouTubeMutation.from(LibraryChange.HistoryPaused(false)).single()

        assertThat(pause.key).isEqualTo(resume.key)
    }

    @Test
    fun `history payloads survive a round trip`() {
        listOf(
            YouTubeMutation.HistoryWatch("vid", 5_000, 10_000),
            YouTubeMutation.HistoryRemove("vid"),
            YouTubeMutation.ClearHistory,
            YouTubeMutation.PauseHistory(true),
        ).forEach { assertThat(YouTubeMutation.decode(YouTubeMutation.encode(it))).isEqualTo(it) }
    }

    @Test
    fun `the history window skips what was removed in Flow`() {
        val window = historyWindow(listOf("a", "b", "c"), floor = emptySet(), removed = setOf("b"))

        assertThat(window.ids).containsExactly("a", "c")
        assertThat(window.reachedFloor).isFalse()
    }

    @Test
    fun `after a Flow-only clear the pull stops at what YouTube had then`() {
        val window = historyWindow(listOf("new1", "new2", "old1", "old2"), floor = setOf("old1", "old2"), removed = emptySet())

        assertThat(window.ids).containsExactly("new1", "new2")
        assertThat(window.reachedFloor).isTrue()
    }

    @Test
    fun `a page that starts at the floor imports nothing`() {
        val window = historyWindow(listOf("old1", "new-but-older?"), floor = setOf("old1"), removed = emptySet())

        assertThat(window.ids).isEmpty()
        assertThat(window.reachedFloor).isTrue()
    }

    @Test
    fun `watch pings carry the viewing's numbers in YouTube's format`() {
        assertThat(watchParams("cpn", 12_345, 60_000))
            .containsExactly("ver", "2", "cpn", "cpn", "cmt", "12.345", "len", "60.000")
        assertThat(watchtimeRange(0, 60_000)).containsExactly("st", "0.000", "et", "60.000", "state", "playing")
    }
}
