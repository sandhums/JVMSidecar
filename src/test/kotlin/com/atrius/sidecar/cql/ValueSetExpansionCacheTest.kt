package com.atrius.sidecar.cql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.engine.runtime.Code
import java.util.concurrent.atomic.AtomicInteger

class ValueSetExpansionCacheTest {

    @AfterEach
    fun clearCache() {
        ValueSetExpansionCache.clear()
    }

    @Test
    fun caches_expansion_per_hts_base_and_valueset_key() {
        val loads = AtomicInteger(0)
        val loader = {
            loads.incrementAndGet()
            listOf(Code().withSystem("http://example.org/cs").withCode("A"))
        }

        val first = ValueSetExpansionCache.getOrExpand("http://127.0.0.1:9091", "vs-1\u0000") { loader() }
        val second = ValueSetExpansionCache.getOrExpand("http://127.0.0.1:9091", "vs-1\u0000") { loader() }
        val otherBase = ValueSetExpansionCache.getOrExpand("http://127.0.0.1:9090", "vs-1\u0000") { loader() }
        val otherVs = ValueSetExpansionCache.getOrExpand("http://127.0.0.1:9091", "vs-2\u0000") { loader() }

        assertEquals(3, loads.get())
        assertSame(first, second)
        assertEquals(1, first.size)
        assertEquals(1, otherBase.size)
        assertEquals(1, otherVs.size)
    }
}
