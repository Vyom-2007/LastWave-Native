package com.lastwave.app.playback.analysis

import android.util.LruCache
import com.lastwave.app.playback.transition.TrackTransitionMetadata

/**
 * In-memory LRU cache for streaming track analysis metadata.
 *
 * Since streaming tracks (YouTube/InnerTube) aren't permanently downloaded,
 * we don't want to bloat the Room database with ephemeral metadata. This
 * cache stores [TrackTransitionMetadata] keyed by track identifier (typically
 * `videoId`) with automatic LRU eviction.
 *
 * If a user skips back to a recently played stream, the metadata is instantly
 * available without re-analysis.
 *
 * **Thread safety:** [LruCache] is internally synchronized. Safe to call from
 * any thread (audio renderer, main, background coroutine).
 *
 * @param maxSize Maximum number of entries before LRU eviction. Default 64
 *                covers a typical listening session queue.
 */
class StreamingMetadataCache(maxSize: Int = DEFAULT_MAX_SIZE) {

    companion object {
        const val DEFAULT_MAX_SIZE = 64
    }

    private val lru = LruCache<String, TrackTransitionMetadata>(maxSize)

    /**
     * Retrieves cached metadata for the given [trackId], or `null` if not cached.
     */
    fun get(trackId: String): TrackTransitionMetadata? = lru.get(trackId)

    /**
     * Stores analysis metadata for the given [trackId]. If the cache is full,
     * the least-recently-used entry is evicted.
     */
    fun put(trackId: String, metadata: TrackTransitionMetadata) {
        lru.put(trackId, metadata)
    }

    /**
     * Returns the current number of cached entries.
     */
    fun size(): Int = lru.size()

    /**
     * Clears all cached entries. Called on app shutdown or memory pressure.
     */
    fun evictAll() {
        lru.evictAll()
    }
}
