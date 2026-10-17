package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.LibraryChange
import io.github.aedev.flow.data.local.LibraryRating
import org.junit.Test
import java.io.IOException

/** The pure rules behind the two-way sync: keys that coalesce, what a send's outcome means, and the pull's diff. */
class YouTubeOutboxRulesTest {
    @Test
    fun `changes to the same thing share a key, so the newest replaces the queued one`() {
        val like = YouTubeMutation.from(LibraryChange.Rating("vid", LibraryRating.LIKED)).single()
        val unlike = YouTubeMutation.from(LibraryChange.Rating("vid", LibraryRating.NONE)).single()
        val other = YouTubeMutation.from(LibraryChange.Rating("vid2", LibraryRating.LIKED)).single()

        assertThat(like.key).isEqualTo(unlike.key)
        assertThat(like.key).isNotEqualTo(other.key)
        assertThat(YouTubeMutation.Subscribe("UCx", on = true).key).isEqualTo(YouTubeMutation.Subscribe("UCx", on = false).key)
        // A rating and a Watch later entry for one video are separate changes.
        assertThat(YouTubeMutation.PlaylistItem("WL", "vid", add = true).key).isNotEqualTo(like.key)
    }

    @Test
    fun `a Watch later change becomes one entry per video, without repeats`() {
        val mutations = YouTubeMutation.from(LibraryChange.WatchLater(listOf("a", "b", "a"), saved = false))

        assertThat(mutations)
            .containsExactly(
                YouTubeMutation.PlaylistItem("WL", "a", add = false),
                YouTubeMutation.PlaylistItem("WL", "b", add = false),
            ).inOrder()
        assertThat(mutations.map { it.key }).containsExactly("pl:WL:a", "pl:WL:b").inOrder()
    }

    @Test
    fun `payloads survive a round trip, and one this build can't read is null`() {
        val mutations =
            listOf(
                YouTubeMutation.Rate("vid", LibraryRating.DISLIKED),
                YouTubeMutation.Subscribe("UCx", on = true),
                YouTubeMutation.PlaylistItem("WL", "vid", add = true),
            )

        mutations.forEach { assertThat(YouTubeMutation.decode(YouTubeMutation.encode(it))).isEqualTo(it) }
        assertThat(YouTubeMutation.decode("""{"type":"bell","channelId":"UCx"}""")).isNull()
        assertThat(YouTubeMutation.decode("not json")).isNull()
    }

    @Test
    fun `queued keys give the ids a pull leaves alone, per list`() {
        val keys = listOf("rate:v1", "sub:UC1", "pl:WL:v2", "rate:v3", "pl:PLx:v4")

        assertThat(YouTubeMutation.idsFor(keys, YouTubeMutation.RATE_PREFIX)).containsExactly("v1", "v3")
        assertThat(YouTubeMutation.idsFor(keys, YouTubeMutation.SUBSCRIBE_PREFIX)).containsExactly("UC1")
        assertThat(YouTubeMutation.idsFor(keys, "pl:WL:")).containsExactly("v2")
    }

    @Test
    fun `send outcomes keep what may still go through and drop what YouTube refused`() {
        assertThat(SendOutcome.of(null)).isEqualTo(SendOutcome.SENT)
        assertThat(SendOutcome.of(IOException("offline"))).isEqualTo(SendOutcome.RETRY)
        assertThat(SendOutcome.of(YouTubeHttpException(429, "slow down"))).isEqualTo(SendOutcome.RETRY)
        assertThat(SendOutcome.of(YouTubeHttpException(503, "down"))).isEqualTo(SendOutcome.RETRY)
        assertThat(SendOutcome.of(YouTubeHttpException(400, "bad"))).isEqualTo(SendOutcome.REJECTED)
        assertThat(SendOutcome.of(YouTubeHttpException(403, "no"))).isEqualTo(SendOutcome.REJECTED)
        assertThat(SendOutcome.of(YouTubeHttpException(404, "gone"))).isEqualTo(SendOutcome.REJECTED)
        assertThat(SendOutcome.of(YouTubeUnauthorizedException())).isEqualTo(SendOutcome.SIGNED_OUT)
        assertThat(SendOutcome.of(IllegalStateException())).isEqualTo(SendOutcome.REJECTED)
    }

    @Test
    fun `the first pull adds everything it read and removes nothing`() {
        val diff = remoteDiff(previous = null, fresh = listOf("a", "b"), complete = false, pending = emptySet(), positional = true)

        assertThat(diff.added).containsExactly("a", "b").inOrder()
        assertThat(diff.removed).isEmpty()
        assertThat(diff.snapshot).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a complete walk finds additions and removals on YouTube`() {
        val diff =
            remoteDiff(listOf("a", "b", "c"), fresh = listOf("d", "a", "c"), complete = true, pending = emptySet(), positional = false)

        assertThat(diff.added).containsExactly("d")
        assertThat(diff.removed).containsExactly("b")
        assertThat(diff.snapshot).containsExactly("d", "a", "c").inOrder()
    }

    @Test
    fun `ids with a queued change are masked both ways`() {
        val diff =
            remoteDiff(
                previous = listOf("a", "b"),
                fresh = listOf("new", "a"),
                complete = true,
                pending = setOf("new", "b"),
                positional = false,
            )

        // "new" was added on YouTube but Flow has a newer change queued for it; "b" vanished but Flow still has one queued.
        assertThat(diff.added).isEmpty()
        assertThat(diff.removed).isEmpty()
    }

    @Test
    fun `local changes not in either list are not the pull's business`() {
        // Flow has "local" from before the outbox; neither list ever had it, so the diff never mentions it.
        val diff = remoteDiff(listOf("a"), fresh = listOf("a"), complete = true, pending = emptySet(), positional = false)

        assertThat(diff.added).isEmpty()
        assertThat(diff.removed).isEmpty()
    }

    @Test
    fun `a partial walk removes only what it would have seen`() {
        val previous = (1..20).map { "v$it" }
        // Two new likes at the front; v3 was unliked on YouTube; the page cap stopped the read at 10.
        val fresh = listOf("n1", "n2") + previous.take(10).filterNot { it == "v3" }.take(8)

        val diff = remoteDiff(previous, fresh, complete = false, pending = emptySet(), positional = true)

        assertThat(diff.added).containsExactly("n1", "n2").inOrder()
        assertThat(diff.removed).containsExactly("v3")
        // Ids past the read stay in the snapshot, unchecked, for a longer walk.
        assertThat(diff.snapshot).containsAtLeastElementsIn(previous.drop(10))
        assertThat(diff.snapshot).doesNotContain("v3")
    }

    @Test
    fun `a partial walk never removes from a list without order, nor near the end of what it read`() {
        val previous = (1..10).map { "v$it" }
        val fresh = previous.take(6).filterNot { it == "v2" }

        assertThat(remoteDiff(previous, fresh, complete = false, pending = emptySet(), positional = false).removed).isEmpty()
        // v6 is in the read; v7 and on are past it or too close to its end to tell.
        assertThat(remoteDiff(previous, fresh, complete = false, pending = emptySet(), positional = true).removed).containsExactly("v2")
    }

    @Test
    fun `an empty read keeps the old snapshot and removes nothing`() {
        val diff = remoteDiff(listOf("a", "b"), fresh = emptyList(), complete = true, pending = emptySet(), positional = false)

        assertThat(diff.removed).isEmpty()
        assertThat(diff.added).isEmpty()
        assertThat(diff.snapshot).containsExactly("a", "b").inOrder()
    }
}
