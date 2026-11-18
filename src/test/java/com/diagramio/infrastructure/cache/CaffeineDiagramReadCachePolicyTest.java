package com.diagramio.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramio.domain.diagram.DiagramCacheKey;
import com.diagramio.domain.diagram.DiagramReadSnapshot;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CaffeineDiagramReadCachePolicyTest {

    @Test
    void ttlExpiresEntryAndCallsLoaderAgain() {
        AtomicLong nowNanos = new AtomicLong();
        Ticker ticker = nowNanos::get;
        CaffeineDiagramReadCache cache =
                new CaffeineDiagramReadCache(Duration.ofNanos(10), 10_000, ticker);
        DiagramCacheKey key = new DiagramCacheKey(UUID.randomUUID(), 1);
        DiagramReadSnapshot snapshot = snapshot("{}");
        AtomicInteger loads = new AtomicInteger();

        assertSame(snapshot, cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return snapshot;
        }));
        assertSame(snapshot, cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return snapshot;
        }));
        assertEquals(1, loads.get(), "second read should hit cache");

        nowNanos.addAndGet(11);
        assertSame(snapshot, cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return snapshot;
        }));
        assertEquals(2, loads.get(), "expired entry should reload");
    }

    @Test
    void weightedEvictionKeepsCacheWithinBudget() {
        // Each "{}" snapshot weighs 2 UTF-8 bytes + 1024 overhead = 1026.
        CaffeineDiagramReadCache cache =
                new CaffeineDiagramReadCache(Duration.ofMinutes(2), 1500, Ticker.systemTicker());
        DiagramReadSnapshot snapshot = snapshot("{}");
        AtomicInteger loads = new AtomicInteger();
        DiagramCacheKey a = new DiagramCacheKey(UUID.randomUUID(), 1);
        DiagramCacheKey b = new DiagramCacheKey(UUID.randomUUID(), 1);

        cache.getOrLoad(a, k -> {
            loads.incrementAndGet();
            return snapshot;
        });
        cache.getOrLoad(b, k -> {
            loads.incrementAndGet();
            return snapshot;
        });
        cache.cleanUp();
        assertTrue(cache.evictionCount() >= 1, "one entry must be evicted");
        assertTrue(cache.estimatedSize() <= 1, "at most one snapshot fits in 1500 bytes");

        cache.getOrLoad(a, k -> {
            loads.incrementAndGet();
            return snapshot;
        });
        cache.getOrLoad(b, k -> {
            loads.incrementAndGet();
            return snapshot;
        });
        assertTrue(loads.get() >= 3, "an evicted entry must need a reload");
    }

    @Test
    void invalidateOneDiagramRemovesAllItsVersionsButKeepsOthers() {
        CaffeineDiagramReadCache cache = new CaffeineDiagramReadCache();
        UUID diagramA = UUID.randomUUID();
        UUID diagramB = UUID.randomUUID();
        DiagramCacheKey aV1 = new DiagramCacheKey(diagramA, 1);
        DiagramCacheKey aV2 = new DiagramCacheKey(diagramA, 2);
        DiagramCacheKey bV1 = new DiagramCacheKey(diagramB, 1);
        DiagramReadSnapshot snapshot = snapshot("{}");
        AtomicInteger loads = new AtomicInteger();

        for (DiagramCacheKey key : new DiagramCacheKey[]{aV1, aV2, bV1}) {
            cache.getOrLoad(key, k -> {
                loads.incrementAndGet();
                return snapshot;
            });
        }
        assertEquals(3, loads.get());
        cache.invalidateDiagram(diagramA);

        cache.getOrLoad(bV1, k -> {
            loads.incrementAndGet();
            return snapshot;
        });
        assertEquals(3, loads.get(), "other diagram must remain cached");
        cache.getOrLoad(aV1, k -> {
            loads.incrementAndGet();
            return snapshot;
        });
        cache.getOrLoad(aV2, k -> {
            loads.incrementAndGet();
            return snapshot;
        });
        assertEquals(5, loads.get(), "both invalidated versions must reload");
    }

    private static DiagramReadSnapshot snapshot(String json) {
        DiagramReadSnapshot snapshot = mock(DiagramReadSnapshot.class, RETURNS_DEEP_STUBS);
        when(snapshot.data().json()).thenReturn(json);
        return snapshot;
    }
}
