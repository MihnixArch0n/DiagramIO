package com.diagramio.domain.diagram;

import com.diagramio.domain.exception.NotFoundException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Version-aware Cache-Aside reader.
 *
 * Keeps Caffeine and JPA details outside the domain.
 * Authorization must be performed before calling this reader.
 */
public final class CachedDiagramReader {

    private static final int MAX_RETRIES = 3;

    private final DiagramRepository diagramRepository;
    private final DiagramRevisionReader revisionReader;
    private final DiagramReadCache readCache;

    public CachedDiagramReader(
            DiagramRepository diagramRepository,
            DiagramRevisionReader revisionReader,
            DiagramReadCache readCache
    ) {
        this.diagramRepository = Objects.requireNonNull(
                diagramRepository, "diagramRepository");
        this.revisionReader = Objects.requireNonNull(
                revisionReader, "revisionReader");
        this.readCache = Objects.requireNonNull(
                readCache, "readCache");
    }

    public DiagramReadSnapshot findById(UUID diagramId) {
        Objects.requireNonNull(
                diagramId, "diagramId must not be null");

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {

            OptionalInt currentVersion =
                    revisionReader.findCurrentVersion(diagramId);

            if (currentVersion.isEmpty()) {
                throw new NotFoundException(
                        "Shared diagram no longer exists");
            }

            DiagramCacheKey key = new DiagramCacheKey(
                    diagramId,
                    currentVersion.getAsInt()
            );

            DiagramReadSnapshot snapshot = readCache.getOrLoad(
                    key,
                    this::loadMatchingVersion
            );

            if (snapshot != null) {
                return snapshot;
            }

            // Version changed while loading.
            // Retry using the latest database version.
        }

        // Persistent contention: use the existing DB read path.
        return loadDirectly(diagramId);
    }

    private DiagramReadSnapshot loadMatchingVersion(
            DiagramCacheKey key
    ) {
        return diagramRepository.findById(key.diagramId())
                .map(DiagramReadSnapshot::from)
                .filter(snapshot ->
                        snapshot.version() == key.version())
                .orElse(null);
    }

    private DiagramReadSnapshot loadDirectly(UUID diagramId) {
        return diagramRepository.findById(diagramId)
                .map(DiagramReadSnapshot::from)
                .orElseThrow(() -> new NotFoundException(
                        "Shared diagram no longer exists"));
    }
}
