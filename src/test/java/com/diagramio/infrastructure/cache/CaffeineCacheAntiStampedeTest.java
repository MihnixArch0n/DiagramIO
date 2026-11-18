package com.diagramio.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramio.domain.diagram.DiagramCacheKey;
import com.diagramio.domain.diagram.DiagramReadSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deterministic evidence that Caffeine coalesces simultaneous cold misses for one key. */
class CaffeineCacheAntiStampedeTest {

    @Test
    void concurrentColdMissesForSameKeyInvokeLoaderOnce() throws Exception {
        CaffeineDiagramReadCache cache = new CaffeineDiagramReadCache();
        DiagramCacheKey key = new DiagramCacheKey(UUID.randomUUID(), 1);
        DiagramReadSnapshot snapshot = snapshot();
        int clients = 24;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<DiagramReadSnapshot>> results = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("start gate timed out");
                    }
                    return cache.getOrLoad(key, ignored -> {
                        loads.incrementAndGet();
                        loaderStarted.countDown();
                        try {
                            if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("loader gate timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("loader interrupted", e);
                        }
                        return snapshot;
                    });
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "not all clients were ready");
            start.countDown();
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS), "loader did not start");
            // Keep the first cache miss in progress so other calls contend on the same key.
            Thread.sleep(150);
            releaseLoader.countDown();
            for (Future<DiagramReadSnapshot> result : results) {
                assertSame(snapshot, result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get(), "one cold key must trigger exactly one loader call");
            assertSame(snapshot, cache.getOrLoad(key, ignored -> {
                loads.incrementAndGet();
                return snapshot;
            }));
            assertEquals(1, loads.get(), "warm read must not call loader again");
        } finally {
            releaseLoader.countDown();
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeysCanLoadWithoutBlockingEachOther() throws Exception {
        CaffeineDiagramReadCache cache = new CaffeineDiagramReadCache();
        DiagramCacheKey first = new DiagramCacheKey(UUID.randomUUID(), 1);
        DiagramCacheKey second = new DiagramCacheKey(UUID.randomUUID(), 1);
        DiagramReadSnapshot snapshot = snapshot();
        CountDownLatch bothLoadersStarted = new CountDownLatch(2);
        CountDownLatch releaseLoaders = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<DiagramReadSnapshot>> results = new ArrayList<>();
            for (DiagramCacheKey key : List.of(first, second)) {
                results.add(pool.submit(() -> cache.getOrLoad(key, ignored -> {
                    bothLoadersStarted.countDown();
                    try {
                        if (!releaseLoaders.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("loader gate timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("loader interrupted", e);
                    }
                    return snapshot;
                })));
            }
            assertTrue(bothLoadersStarted.await(5, TimeUnit.SECONDS),
                    "independent cache keys should enter their loaders concurrently");
            releaseLoaders.countDown();
            for (Future<DiagramReadSnapshot> result : results) {
                assertSame(snapshot, result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            releaseLoaders.countDown();
            pool.shutdownNow();
        }
    }

    private static DiagramReadSnapshot snapshot() {
        DiagramReadSnapshot snapshot = mock(DiagramReadSnapshot.class, RETURNS_DEEP_STUBS);
        when(snapshot.data().json()).thenReturn("{\"nodes\":[],\"edges\":[]}");
        return snapshot;
    }
}
