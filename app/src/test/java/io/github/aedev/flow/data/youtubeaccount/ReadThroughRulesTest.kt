package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.LibraryRating
import org.junit.Test

/** When the watch page's like and subscribe state replaces Flow's. */
class ReadThroughRulesTest {
    private fun fix(
        remoteLike: String? = null,
        localLike: String? = null,
        likePending: Boolean = false,
        remoteSubscribed: Boolean? = null,
        localSubscribed: Boolean = false,
        subscriptionPending: Boolean = false,
    ) = readThroughFix(remoteLike, localLike, likePending, remoteSubscribed, localSubscribed, subscriptionPending)

    @Test
    fun `a like or dislike made elsewhere comes in`() {
        assertThat(fix(remoteLike = "LIKE").like).isEqualTo(LibraryRating.LIKED)
        assertThat(fix(remoteLike = "DISLIKE", localLike = "LIKED").like).isEqualTo(LibraryRating.DISLIKED)
        assertThat(fix(remoteLike = "INDIFFERENT", localLike = "LIKED").like).isEqualTo(LibraryRating.NONE)
    }

    @Test
    fun `matching state changes nothing`() {
        val same = fix(remoteLike = "LIKE", localLike = "LIKED", remoteSubscribed = true, localSubscribed = true)

        assertThat(same).isEqualTo(ReadThroughFix(like = null, subscribed = null))
        assertThat(fix(remoteLike = "INDIFFERENT").like).isNull()
    }

    @Test
    fun `Flow's own change still queued wins`() {
        assertThat(fix(remoteLike = "INDIFFERENT", localLike = "LIKED", likePending = true).like).isNull()
        assertThat(fix(remoteSubscribed = false, localSubscribed = true, subscriptionPending = true).subscribed).isNull()
    }

    @Test
    fun `a subscription changed elsewhere comes in both ways`() {
        assertThat(fix(remoteSubscribed = true).subscribed).isTrue()
        assertThat(fix(remoteSubscribed = false, localSubscribed = true).subscribed).isFalse()
    }

    @Test
    fun `a page without the controls says nothing`() {
        assertThat(fix(remoteLike = null, localLike = "LIKED", remoteSubscribed = null, localSubscribed = true))
            .isEqualTo(ReadThroughFix(like = null, subscribed = null))
        assertThat(fix(remoteLike = "SOMETHING_NEW", localLike = "LIKED").like).isNull()
    }
}
