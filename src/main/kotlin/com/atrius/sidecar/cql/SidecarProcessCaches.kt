package com.atrius.sidecar.cql

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration

/**
 * Bounded process caches. Content / expand entries expire so same-version KR re-imports
 * become visible without a manual flush; compiled ELM stacks live longer because identity
 * keys already invalidate on `meta.versionId` change.
 */
internal object SidecarProcessCaches {
    const val CONTENT_MAX_SIZE = 2_048L
    val CONTENT_EXPIRE: Duration = Duration.ofSeconds(60)
    const val STACK_MAX_SIZE = 256L
    val STACK_EXPIRE: Duration = Duration.ofMinutes(30)

    fun <K : Any, V : Any> contentCache(): Cache<K, V> =
        Caffeine.newBuilder()
            .maximumSize(CONTENT_MAX_SIZE)
            .expireAfterWrite(CONTENT_EXPIRE)
            .build()

    fun <K : Any, V : Any> stackCache(): Cache<K, V> =
        Caffeine.newBuilder()
            .maximumSize(STACK_MAX_SIZE)
            .expireAfterWrite(STACK_EXPIRE)
            .build()

    fun estimatedSize(cache: Cache<*, *>): Int {
        cache.cleanUp()
        return cache.asMap().size
    }

    fun invalidateAll(cache: Cache<*, *>): Int {
        val n = estimatedSize(cache)
        cache.invalidateAll()
        cache.cleanUp()
        return n
    }
}
