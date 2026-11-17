package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.DiagramCacheKey;
import com.diagramio.domain.diagram.DiagramReadCache;
import com.diagramio.domain.diagram.DiagramReadSnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Caffeine implementation of version-aware read caching.
 *
 * Stores immutable snapshots only.
 * Authentication and authorization are never cached.
 */
@Component
public class CaffeineDiagramReadCache
        implements DiagramReadCache {

    private static final long MAX_WEIGHT =
            64L * 1024 * 1024;

    private static final Duration TTL =
            Duration.ofMinutes(2);

    private final Cache<DiagramCacheKey, DiagramReadSnapshot> cache;

    public CaffeineDiagramReadCache() {
        Weigher<DiagramCacheKey, DiagramReadSnapshot> weigher =
                (key, snapshot) -> {
                    int bytes = snapshot.data()
                            .json()
                            .getBytes(StandardCharsets.UTF_8)
                            .length;

                    return bytes + 1024;
                };

        this.cache = Caffeine.newBuilder()
                .maximumWeight(MAX_WEIGHT)
                .weigher(weigher)
                .expireAfterWrite(TTL)
                .recordStats()
                .build();
    }

    @Override
    public DiagramReadSnapshot getOrLoad(
            DiagramCacheKey key,
            Function<DiagramCacheKey, DiagramReadSnapshot> loader
    ) {
        Objects.requireNonNull(
                key, "key must not be null");
        Objects.requireNonNull(
                loader, "loader must not be null");

        return cache.get(key, loader);
    }

    @Override
    public void invalidateDiagram(UUID diagramId) {
        Objects.requireNonNull(
                diagramId,
                "diagramId must not be null"
        );

        cache.asMap().keySet().removeIf(
                key -> key.diagramId().equals(diagramId)
        );
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }

    public double hitRate() {
        return cache.stats().hitRate();
    }

    public long evictionCount() {
        return cache.stats().evictionCount();
    }
}
