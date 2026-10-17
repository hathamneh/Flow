package io.github.aedev.flow.data.youtubeaccount

/**
 * The moves that put a playlist's entries on YouTube ([remote], in order) into Flow's order ([wanted]),
 * using only "move after", the one move YouTube's edit takes. Videos on one side only stay where they
 * are, and a video YouTube has twice moves by its first entry.
 *
 * The longest run already in order stays put and everything else moves behind its new predecessor, so
 * dragging one video costs one move. The top has no predecessor: the video moves behind the current
 * first one, which then moves behind it.
 */
fun planMoves(
    remote: List<PlaylistEntry>,
    wanted: List<String>,
): List<PlaylistEdit.MoveAfter> {
    val entries = LinkedHashMap<String, String>()
    remote.forEach { entry -> entry.setVideoId?.let { entries.putIfAbsent(entry.videoId, it) } }
    val target = wanted.distinct().filter { it in entries }
    if (target.size < 2) return emptyList()
    val rank = target.withIndex().associate { (index, id) -> id to index }
    val order = entries.keys.filter { it in rank }.toMutableList()
    val stable = longestIncreasingRun(order.map { rank.getValue(it) }).mapTo(HashSet()) { order[it] }

    val moves = mutableListOf<PlaylistEdit.MoveAfter>()

    fun move(
        id: String,
        after: String,
    ) {
        moves += PlaylistEdit.MoveAfter(setVideoId = entries.getValue(id), predecessorSetVideoId = entries.getValue(after))
        order.remove(id)
        order.add(order.indexOf(after) + 1, id)
    }
    target.forEachIndexed { index, id ->
        when {
            id in stable -> {
                Unit
            }

            index > 0 -> {
                move(id, after = target[index - 1])
            }

            order.first() != id -> {
                val first = order.first()
                move(id, after = first)
                move(first, after = id)
            }
        }
    }
    return moves
}

/** Indices into [values] of one longest strictly increasing subsequence. */
private fun longestIncreasingRun(values: List<Int>): List<Int> {
    if (values.isEmpty()) return emptyList()
    // tails[k]: index of the smallest value ending a run of length k + 1.
    val tails = IntArray(values.size)
    val previous = IntArray(values.size) { -1 }
    var length = 0
    values.forEachIndexed { index, value ->
        var low = 0
        var high = length
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[tails[mid]] < value) low = mid + 1 else high = mid
        }
        if (low > 0) previous[index] = tails[low - 1]
        tails[low] = index
        if (low == length) length++
    }
    val run = ArrayList<Int>(length)
    var at = tails[length - 1]
    while (at >= 0) {
        run += at
        at = previous[at]
    }
    return run.asReversed()
}
