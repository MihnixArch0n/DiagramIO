package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.DiagramCacheKey;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramReadSnapshot;
import com.diagramio.domain.diagram.DiagramType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CaffeineDiagramReadCacheTest {

    private final CaffeineDiagramReadCache cache =
            new CaffeineDiagramReadCache();

    private DiagramReadSnapshot snapshot(UUID id, int version) {
        return new DiagramReadSnapshot(
                id,
                "Test ERD",
                DiagramType.ERD,
                DiagramData.EMPTY,
                version,
                false,
                Instant.EPOCH,
                Instant.EPOCH
        );
    }

    @Test
    void repeatedReadShouldHitCache() {
        UUID id = UUID.randomUUID();
        DiagramCacheKey key = new DiagramCacheKey(id, 1);
        AtomicInteger loads = new AtomicInteger();

        DiagramReadSnapshot first = cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return snapshot(id, 1);
        });

        DiagramReadSnapshot second = cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return snapshot(id, 1);
        });

        assertSame(first, second);
        assertEquals(1, loads.get());
        assertEquals(0.5, cache.hitRate(), 0.001);
    }

    @Test
    void differentVersionShouldUseDifferentCacheKey() {
        UUID id = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();

        DiagramReadSnapshot first = cache.getOrLoad(
                new DiagramCacheKey(id, 1), k -> {
                    loads.incrementAndGet();
                    return snapshot(id, 1);
                });

        DiagramReadSnapshot second = cache.getOrLoad(
                new DiagramCacheKey(id, 2), k -> {
                    loads.incrementAndGet();
                    return snapshot(id, 2);
                });

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals(2, loads.get());
    }

    @Test
    void invalidationShouldCauseReload() {
        UUID id = UUID.randomUUID();
        DiagramCacheKey key = new DiagramCacheKey(id, 1);
        AtomicInteger loads = new AtomicInteger();

        cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return snapshot(id, 1);
        });

        cache.invalidateDiagram(id);

        cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return snapshot(id, 1);
        });

        assertEquals(2, loads.get());
    }

    @Test
    void nullResultShouldNotBeCached() {
        UUID id = UUID.randomUUID();
        DiagramCacheKey key = new DiagramCacheKey(id, 1);
        AtomicInteger loads = new AtomicInteger();

        assertNull(cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return null;
        }));

        assertNull(cache.getOrLoad(key, k -> {
            loads.incrementAndGet();
            return null;
        }));

        assertEquals(2, loads.get());
        assertEquals(0, cache.estimatedSize());
    }
}
