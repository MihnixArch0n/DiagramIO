package com.diagramio.domain.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiagramCommandServiceTest {

    @Mock
    private DiagramRepository repository;

    private DiagramCommandService service;

    @BeforeEach
    void setUp() {
        service = new DiagramCommandService(repository);
    }

    @Test
    void createDiagramUsesDefaultDataAndPrivateVisibility() {
        when(repository.save(any(Diagram.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Diagram diagram = new Diagram();
        diagram.setTitle("Orders");
        diagram.setDiagramType(DiagramType.ERD);
        diagram.setData(null);
        diagram.setPublic(true);

        Diagram created = service.createDiagram(diagram);

        assertEquals(DiagramData.EMPTY, created.getData());
        assertFalse(created.isPublic());
        assertEquals(1, created.getVersion());
        verify(repository).save(diagram);
    }

    @Test
    void createDiagramRequiresDiagramAndType() {
        assertThrows(InvalidInputException.class, () -> service.createDiagram(null));

        Diagram diagramWithoutType = new Diagram();
        diagramWithoutType.setTitle("No Type");
        assertThrows(InvalidInputException.class, () -> service.createDiagram(diagramWithoutType));
    }

    @Test
    void updateDiagramSavesWithCheckedVersionWhenRequestVersionMatches() {
        UUID id = UUID.randomUUID();
        Diagram current = diagram(id, 3);
        when(repository.findMetadataById(id)).thenReturn(Optional.of(current));
        when(repository.save(any(Diagram.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Diagram updated = service.updateDiagram(
                id, "Updated", DiagramType.UML, DiagramData.fromStorage("{\"nodes\": [], \"edges\": []}"), false,
                Caller.user(current.getOwnerId()), 3);

        assertEquals(3, updated.getVersion());
        assertEquals("Updated", updated.getTitle());
        verify(repository).save(current);
    }

    @Test
    void updateDiagramRejectsStaleVersion() {
        UUID id = UUID.randomUUID();
        Diagram current = diagram(id, 3);
        when(repository.findMetadataById(id)).thenReturn(Optional.of(current));

        assertThrows(OptimisticLockConflictException.class, () -> service.updateDiagram(
                id, "Updated", DiagramType.UML, DiagramData.fromStorage("{\"nodes\": [], \"edges\": []}"), false,
                Caller.user(current.getOwnerId()), 2));

        verify(repository, never()).save(any(Diagram.class));
    }

    @Test
    void updateDiagramRequiresData() {
        UUID id = UUID.randomUUID();
        Diagram current = diagram(id, 1);
        when(repository.findMetadataById(id)).thenReturn(Optional.of(current));

        assertThrows(InvalidInputException.class, () -> service.updateDiagram(
                id, "Orders", DiagramType.ERD, null, false, Caller.user(current.getOwnerId()), 1));
        verify(repository, never()).save(any(Diagram.class));
    }

    @Test
    void updateDiagramRejectsStranger() {
        UUID id = UUID.randomUUID();
        when(repository.findMetadataById(id)).thenReturn(Optional.of(diagram(id, 1)));

        assertThrows(ForbiddenException.class, () -> service.updateDiagram(
                id, "Hacked", DiagramType.ERD, DiagramData.EMPTY, false, Caller.user(UUID.randomUUID()), 1));

        verify(repository, never()).save(any(Diagram.class));
    }

    @Test
    void updateDiagramRejectsViewShareToken() {
        UUID id = UUID.randomUUID();
        when(repository.findMetadataById(id)).thenReturn(Optional.of(diagram(id, 1)));

        assertThrows(ForbiddenException.class, () -> service.updateDiagram(
                id, "Updated", DiagramType.ERD, DiagramData.EMPTY, false,
                Caller.shareToken(id, ShareRole.VIEW), 1));

        verify(repository, never()).save(any(Diagram.class));
    }

    @Test
    void editShareTokenCanUpdateButCannotChangeVisibility() {
        UUID id = UUID.randomUUID();
        when(repository.findMetadataById(id)).thenReturn(Optional.of(diagram(id, 1)));
        when(repository.save(any(Diagram.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Diagram updated = service.updateDiagram(id, "Updated", DiagramType.ERD, DiagramData.EMPTY, true,
                Caller.shareToken(id, ShareRole.EDIT), 1);

        assertEquals("Updated", updated.getTitle());
        assertFalse(updated.isPublic());
        assertNull(updated.getUpdatedBy());
    }

    @Test
    void loggedInEditorIsRecordedAsUpdatedBy() {
        UUID id = UUID.randomUUID();
        UUID editorId = UUID.randomUUID();
        when(repository.findMetadataById(id)).thenReturn(Optional.of(diagram(id, 1)));
        when(repository.save(any(Diagram.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Diagram updated = service.updateDiagram(id, "Updated", DiagramType.ERD, DiagramData.EMPTY, false,
                new Caller(editorId, id, ShareRole.EDIT), 1);

        assertEquals(editorId, updated.getUpdatedBy());
    }

    @Test
    void ownerCanMakeDiagramPublic() {
        UUID id = UUID.randomUUID();
        Diagram current = diagram(id, 1);
        when(repository.findMetadataById(id)).thenReturn(Optional.of(current));
        when(repository.save(any(Diagram.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Diagram updated = service.updateDiagram(id, "Orders", DiagramType.ERD, DiagramData.EMPTY, true,
                Caller.user(current.getOwnerId()), 1);

        assertTrue(updated.isPublic());
        assertEquals(current.getOwnerId(), updated.getUpdatedBy());
    }

    @Test
    void deleteDiagramRejectsNonOwner() {
        UUID id = UUID.randomUUID();
        Diagram diagram = diagram(id, 1);
        when(repository.findMetadataById(id)).thenReturn(Optional.of(diagram));

        assertThrows(ForbiddenException.class,
                () -> service.deleteDiagram(id, Caller.user(UUID.randomUUID())));
        assertThrows(ForbiddenException.class,
                () -> service.deleteDiagram(id, Caller.shareToken(id, ShareRole.EDIT)));

        verify(repository, never()).deleteById(id);
    }

    @Test
    void ownerCanDeleteDiagram() {
        UUID id = UUID.randomUUID();
        Diagram diagram = diagram(id, 1);
        when(repository.findMetadataById(id)).thenReturn(Optional.of(diagram));

        service.deleteDiagram(id, Caller.user(diagram.getOwnerId()));

        verify(repository).deleteById(id);
    }

    private Diagram diagram(UUID id, int version) {
        return new Diagram(id, "Orders", DiagramType.ERD, DiagramData.EMPTY, version,
                false, UUID.randomUUID(), null, null, null);
    }
}
