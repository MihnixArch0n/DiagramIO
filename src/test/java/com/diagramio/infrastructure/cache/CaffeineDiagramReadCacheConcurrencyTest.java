package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.DiagramCacheKey;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramReadSnapshot;
import com.diagramio.domain.diagram.DiagramType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CaffeineDiagramReadCacheConcurrencyTest {

    @Test
    void fiftyConcurrentRequestsShouldLoadOnce() throws Exception {
        int users = 50;

        CaffeineDiagramReadCache cache =
                new CaffeineDiagramReadCache();

        UUID id = UUID.randomUUID();
        DiagramCacheKey key = new DiagramCacheKey(id, 1);

        AtomicInteger loaderCalls = new AtomicInteger();

        DiagramReadSnapshot expected = new DiagramReadSnapshot(
                id,
                "Concurrent ERD",
                DiagramType.ERD,
                DiagramData.EMPTY,
                1,
                false,
                Instant.EPOCH,
                Instant.EPOCH
        );

        CountDownLatch ready = new CountDownLatch(users);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(users);

        try {
            List<Future<DiagramReadSnapshot>> futures =
                    new ArrayList<>();

            for (int i = 0; i < users; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Start gate timed out"
                        );
                    }

                    return cache.getOrLoad(key, k -> {
                        loaderCalls.incrementAndGet();

                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }

                        return expected;
                    });
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "All workers must be ready"
            );

            start.countDown();

            for (Future<DiagramReadSnapshot> future : futures) {
                assertSame(
                        expected,
                        future.get(15, TimeUnit.SECONDS)
                );
            }

            assertEquals(
                    1,
                    loaderCalls.get(),
                    "Concurrent requests must share one cache load"
            );

        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
