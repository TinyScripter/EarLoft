package de.kleinabersmart.earloft.core.playback

/**
 * The results of the last search in Android Auto. A search ([fresh], `onSearch`) always looks again: a sync may have brought new
 * content since the same term was searched before (review5 P18). The pages that follow it ([page], `onGetSearchResult`) reuse
 * those results, so the list doesn't shift between pages. Not thread-safe: used on the main thread only.
 */
internal class LastSearch<K, V> {
    private var last: Pair<K, V>? = null

    suspend fun fresh(key: K, search: suspend () -> V): V = search().also { last = key to it }

    suspend fun page(key: K, search: suspend () -> V): V = last?.takeIf { it.first == key }?.second ?: fresh(key, search)
}
