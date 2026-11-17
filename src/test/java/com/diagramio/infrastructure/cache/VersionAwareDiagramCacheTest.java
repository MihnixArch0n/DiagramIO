package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.*;
import com.diagramio.domain.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersionAwareDiagramCacheTest {

    private final UUID id = UUID.randomUUID();

    private final DiagramRepository repository =
            mock(DiagramRepository.class);

    private final DiagramRevisionReader revisions =
            mock(DiagramRevisionReader.class);

    private final CaffeineDiagramReadCache cache =
            new CaffeineDiagramReadCache();

    private final CachedDiagramReader reader =
            new CachedDiagramReader(
                    repository, revisions, cache);

    private Diagram diagram(int version) {
        return new Diagram(
                id,
                "Test ERD",
                DiagramType.ERD,
                DiagramData.EMPTY,
                version,
                false,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH
        );
    }

    @Test
    void sameVersionShouldReuseCache() {
        when(revisions.findCurrentVersion(id))
                .thenReturn(OptionalInt.of(1));

        when(repository.findById(id))
                .thenReturn(Optional.of(diagram(1)));

        DiagramReadSnapshot first = reader.findById(id);
        DiagramReadSnapshot second = reader.findById(id);

        assertSame(first, second);
        assertEquals(1, first.version());

        verify(repository, times(1)).findById(id);
        verify(revisions, times(2)).findCurrentVersion(id);
    }

    @Test
    void newVersionShouldNotReturnOldSnapshot() {
        when(revisions.findCurrentVersion(id))
                .thenReturn(
                        OptionalInt.of(1),
                        OptionalInt.of(2)
                );

        when(repository.findById(id))
                .thenReturn(
                        Optional.of(diagram(1)),
                        Optional.of(diagram(2))
                );

        DiagramReadSnapshot first = reader.findById(id);
        DiagramReadSnapshot second = reader.findById(id);

        assertEquals(1, first.version());
        assertEquals(2, second.version());

        verify(repository, times(2)).findById(id);
    }

    @Test
    void versionChangeDuringLoadShouldRetry() {
        when(revisions.findCurrentVersion(id))
                .thenReturn(
                        OptionalInt.of(1),
                        OptionalInt.of(2)
                );

        // DB is already at version 2 when full read happens.
        when(repository.findById(id))
                .thenReturn(Optional.of(diagram(2)));

        DiagramReadSnapshot result = reader.findById(id);

        assertEquals(2, result.version());

        verify(revisions, times(2)).findCurrentVersion(id);
        verify(repository, times(2)).findById(id);
    }

    @Test
    void deletedDiagramMustNotUseOldCache() {
        when(revisions.findCurrentVersion(id))
                .thenReturn(
                        OptionalInt.of(1),
                        OptionalInt.empty()
                );

        when(repository.findById(id))
                .thenReturn(Optional.of(diagram(1)));

        assertEquals(1, reader.findById(id).version());

        assertThrows(
                NotFoundException.class,
                () -> reader.findById(id)
        );

        // Second request must not load the old cached content.
        verify(repository, times(1)).findById(id);
    }
}
