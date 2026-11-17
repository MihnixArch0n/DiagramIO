package com.diagramio.domain.diagram;

import java.util.UUID;
import java.util.function.Function;

/**
 * Port for version-aware read caching.
 *
 * Domain code must not depend directly on Caffeine.
 */
public interface DiagramReadCache {

    /**
     * Returns cached content or loads it when missing.
     *
     * A loader may return null when the requested version
     * is no longer current. Null must not be cached.
     *
     * Implementations should coalesce concurrent loads
     * for the same cache key.
     */
    DiagramReadSnapshot getOrLoad(
            DiagramCacheKey key,
            Function<DiagramCacheKey, DiagramReadSnapshot> loader
    );

    /**
     * Removes cached versions of a diagram.
     *
     * Intended for post-commit cleanup.
     */
    void invalidateDiagram(UUID diagramId);
}
