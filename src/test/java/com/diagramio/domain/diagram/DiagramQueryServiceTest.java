package com.diagramio.domain.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.UnauthorizedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiagramQueryServiceTest {

    @Mock
    private DiagramRepository repository;

    private DiagramQueryService service;

    @BeforeEach
    void setUp() {
        service = new DiagramQueryService(repository);
    }

    @Test
    void pageSizeIsLimited() {
        UUID ownerId = UUID.randomUUID();
        assertThrows(InvalidInputException.class,
                () -> service.getDiagramSummariesByOwner(ownerId, null, null, 0, DiagramQueryService.MAX_PAGE_SIZE + 1));
        assertThrows(InvalidInputException.class, () -> service.getPublicDiagramSummaries(0, 0));
        assertThrows(InvalidInputException.class, () -> service.getPublicDiagramSummaries(-1, 20));
    }

    @Test
    void getDiagramByIdRejectsAnonymousForPrivateDiagramWith401() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(diagram(id, 1)));

        // Anonymous caller (no account, no link): authentication might change the result, so return 401 instead of 403
        assertThrows(UnauthorizedException.class,
                () -> service.getDiagramById(id, Caller.anonymous()));
    }

    @Test
    void getDiagramByIdAllowsViewShareTokenAndPublicDiagram() {
        UUID id = UUID.randomUUID();
        Diagram diagram = diagram(id, 1);
        when(repository.findById(id)).thenReturn(Optional.of(diagram));

        assertEquals(diagram, service.getDiagramById(id, Caller.shareToken(id, ShareRole.VIEW)));

        diagram.setPublic(true);
        assertEquals(diagram, service.getDiagramById(id, Caller.anonymous()));
    }

    @Test
    void getDiagramSummariesByOwnerDelegatesToRepository() {
        UUID ownerId = UUID.randomUUID();
        DiagramSummary summary = new DiagramSummary(UUID.randomUUID(), "Orders", DiagramType.ERD, 1,
                false, ownerId, null, null, null);
        when(repository.findSummariesByOwner(ownerId, DiagramType.ERD, "Orders", 0, 10))
                .thenReturn(List.of(summary));

        List<DiagramSummary> result = service.getDiagramSummariesByOwner(ownerId, DiagramType.ERD, "  Orders  ", 0, 10);

        assertEquals(1, result.size());
        assertEquals(summary, result.getFirst());
        verify(repository).findSummariesByOwner(ownerId, DiagramType.ERD, "Orders", 0, 10);
    }

    @Test
    void countDiagramsByOwnerDelegatesToRepository() {
        UUID ownerId = UUID.randomUUID();
        when(repository.countByOwnerId(ownerId, DiagramType.ERD, "Orders")).thenReturn(5L);

        long count = service.countDiagramsByOwner(ownerId, DiagramType.ERD, "  Orders  ");

        assertEquals(5L, count);
        verify(repository).countByOwnerId(ownerId, DiagramType.ERD, "Orders");
    }

    @Test
    void getPublicDiagramSummariesDelegatesToRepository() {
        DiagramSummary summary = new DiagramSummary(UUID.randomUUID(), "Public", DiagramType.UML, 1,
                true, UUID.randomUUID(), null, null, null);
        when(repository.findPublicDiagramSummaries(0, 20)).thenReturn(List.of(summary));

        List<DiagramSummary> result = service.getPublicDiagramSummaries(0, 20);

        assertEquals(1, result.size());
        assertEquals(summary, result.getFirst());
        verify(repository).findPublicDiagramSummaries(0, 20);
    }

    @Test
    void countPublicDiagramsDelegatesToRepository() {
        when(repository.countPublicDiagrams()).thenReturn(42L);

        long count = service.countPublicDiagrams();

        assertEquals(42L, count);
        verify(repository).countPublicDiagrams();
    }

    private Diagram diagram(UUID id, int version) {
        return new Diagram(id, "Orders", DiagramType.ERD, DiagramData.EMPTY, version,
                false, UUID.randomUUID(), null, null, null);
    }
}
