package varro.host

import java.util.PriorityQueue
import java.util.concurrent.CancellationException

/** Bounded ranking over project VFS entries, including extensionless files and path matches. */
internal class FileSearch<T>(query: String, limit: Int, private val cancelled: () -> Boolean = { false }) {
    private val query = query.trim().replace('\\', '/').lowercase()
    private val limit = limit.coerceIn(1, 100)
    private data class Match<T>(val value: T, val path: String, val rank: Int)
    private val order = compareBy<Match<T>> { it.rank }.thenBy { it.path }
    private val matches = PriorityQueue(order.reversed())

    fun offer(value: T, relativePath: String) {
        if (cancelled()) throw CancellationException("File search superseded")
        val path = relativePath.replace('\\', '/')
        val lower = path.lowercase()
        if (!lower.contains(query)) return
        val name = lower.substringAfterLast('/')
        val rank = when {
            name == query -> 0
            name.startsWith(query) -> 1_000
            name.contains(query) -> 2_000
            else -> 3_000
        } + path.count { it == '/' }
        matches.add(Match(value, path, rank))
        if (matches.size > limit) matches.poll()
    }

    fun results(): List<T> = matches.sortedWith(order).map { it.value }
}
