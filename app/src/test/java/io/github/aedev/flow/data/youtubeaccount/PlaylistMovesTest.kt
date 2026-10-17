package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.LibraryChange
import org.junit.Test
import kotlin.random.Random

/** Reordering an owned playlist on YouTube with only "move after", and the playlist changes the outbox keeps. */
class PlaylistMovesTest {
    @Test
    fun `the same order needs no moves`() {
        assertThat(planMoves(entries("a", "b", "c"), listOf("a", "b", "c"))).isEmpty()
    }

    @Test
    fun `dragging one video down costs one move`() {
        val moves = planMoves(entries("a", "b", "c", "d"), listOf("b", "c", "d", "a"))

        assertThat(moves).containsExactly(PlaylistEdit.MoveAfter("s-a", "s-d"))
    }

    @Test
    fun `moving to the top goes behind the first video, which then moves behind it`() {
        val moves = planMoves(entries("a", "b", "c", "d"), listOf("d", "a", "b", "c"))

        assertThat(moves).containsExactly(PlaylistEdit.MoveAfter("s-d", "s-a"), PlaylistEdit.MoveAfter("s-a", "s-d")).inOrder()
        assertThat(apply(listOf("s-a", "s-b", "s-c", "s-d"), moves)).containsExactly("s-d", "s-a", "s-b", "s-c").inOrder()
    }

    @Test
    fun `videos on one side only stay put, and a repeated video moves by its first entry`() {
        val remote = entries("a", "x", "b", "c") + PlaylistEntry("a", "s-a2")
        val moves = planMoves(remote, listOf("c", "b", "a", "new"))
        val result = apply(remote.map { it.setVideoId!! }, moves).filterNot { it == "s-x" || it == "s-a2" }

        assertThat(result).containsExactly("s-c", "s-b", "s-a").inOrder()
    }

    @Test
    fun `entries without a setVideoId can't move and are left out`() {
        val remote = listOf(PlaylistEntry("a", null)) + entries("b", "c")

        assertThat(planMoves(remote, listOf("c", "a", "b"))).containsExactly(PlaylistEdit.MoveAfter("s-b", "s-c"))
    }

    @Test
    fun `any shuffle comes out in Flow's order with no more moves than videos out of place, plus one`() {
        val random = Random(7)
        repeat(500) {
            val ids = (0 until random.nextInt(2, 30)).map { "v$it" }
            val remote = ids.shuffled(random)
            val wanted = ids.shuffled(random)
            val moves = planMoves(entries(*remote.toTypedArray()), wanted)

            assertThat(apply(remote.map { "s-$it" }, moves)).isEqualTo(wanted.map { "s-$it" })
            assertThat(moves.size).isAtMost(ids.size + 1)
        }
    }

    @Test
    fun `playlist changes coalesce per playlist, and a delete sends nothing`() {
        val items = YouTubeMutation.from(LibraryChange.PlaylistItems("PLx", listOf("a", "a", "b"), added = true))
        assertThat(items.map { it.key }).containsExactly("pl:PLx:a", "pl:PLx:b").inOrder()

        val rename = YouTubeMutation.from(LibraryChange.PlaylistRename("PLx", "One")).single()
        assertThat(rename.key).isEqualTo(YouTubeMutation.from(LibraryChange.PlaylistRename("PLx", "Two")).single().key)
        assertThat(YouTubeMutation.from(LibraryChange.PlaylistOrder("PLx", "PLx")).single().key).isEqualTo("plorder:PLx")
        assertThat(YouTubeMutation.from(LibraryChange.PlaylistSaved("PLy", saved = false)).single().key).isEqualTo("plsave:PLy")
        assertThat(YouTubeMutation.from(LibraryChange.PlaylistDeleted("PLx"))).isEmpty()
    }

    @Test
    fun `playlist payloads survive a round trip`() {
        val mutations =
            listOf(
                YouTubeMutation.PlaylistOrder("PLx", "PLx"),
                YouTubeMutation.PlaylistRename("PLx", "Road trip, 2026"),
                YouTubeMutation.PlaylistSave("PLy", saved = true),
            )

        mutations.forEach { assertThat(YouTubeMutation.decode(YouTubeMutation.encode(it))).isEqualTo(it) }
    }

    private fun entries(vararg ids: String) = ids.map { PlaylistEntry(it, "s-$it") }

    /** YouTube's "move after": the entry goes right behind its predecessor. */
    private fun apply(
        order: List<String>,
        moves: List<PlaylistEdit.MoveAfter>,
    ): List<String> {
        val result = order.toMutableList()
        moves.forEach { move ->
            result.remove(move.setVideoId)
            result.add(result.indexOf(move.predecessorSetVideoId) + 1, move.setVideoId)
        }
        return result
    }
}
