package com.diagramio.domain.share;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.*;
import com.diagramio.domain.exception.NotFoundException;
import com.diagramio.infrastructure.cache.CaffeineDiagramReadCache;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareServiceCacheSecurityTest {

    private final UUID diagramId = UUID.randomUUID();

    private final DiagramShareRepository shares =
            mock(DiagramShareRepository.class);

    private final DiagramRepository diagrams =
            mock(DiagramRepository.class);

    private final DiagramRevisionReader revisions =
            mock(DiagramRevisionReader.class);

    private final CaffeineDiagramReadCache cache =
            new CaffeineDiagramReadCache();

    private final ShareService service = new ShareService(
            shares,
            diagrams,
            new CachedDiagramReader(diagrams, revisions, cache)
    );

    private DiagramShare share(String token, ShareRole role) {
        return new DiagramShare(
                UUID.randomUUID(),
                diagramId,
                token,
                role,
                Instant.EPOCH
        );
    }

    private void stubDiagram() {
        Diagram diagram = new Diagram(
                diagramId,
                "Security Test ERD",
                DiagramType.ERD,
                DiagramData.EMPTY,
                1,
                false,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.EPOCH,
                Instant.EPOCH
        );

        when(revisions.findCurrentVersion(diagramId))
                .thenReturn(OptionalInt.of(1));

        when(diagrams.findById(diagramId))
                .thenReturn(Optional.of(diagram));
    }

    @Test
    void revokedTokenMustNotAccessCachedDiagram() {
        String token = "revoked-token";

        when(shares.findByShareToken(token))
                .thenReturn(
                        Optional.of(share(token, ShareRole.VIEW)),
                        Optional.empty()
                );

        stubDiagram();

        // First request loads the diagram into cache.
        assertNotNull(service.getDiagramByShareToken(token));

        // Simulate token revocation in the database.
        assertThrows(
                NotFoundException.class,
                () -> service.getDiagramByShareToken(token)
        );

        verify(shares, times(2)).findByShareToken(token);
        verify(revisions, times(1))
                .findCurrentVersion(diagramId);
        verify(diagrams, times(1)).findById(diagramId);
    }

    @Test
    void changedShareRoleMustNotUseOldPermission() {
        String token = "role-token";

        when(shares.findByShareToken(token))
                .thenReturn(
                        Optional.of(share(token, ShareRole.EDIT)),
                        Optional.of(share(token, ShareRole.VIEW))
                );

        stubDiagram();

        SharedDiagramView first =
                service.getDiagramByShareToken(token);

        SharedDiagramView second =
                service.getDiagramByShareToken(token);

        assertEquals(ShareRole.EDIT, first.getRole());
        assertEquals(ShareRole.VIEW, second.getRole());

        // Content is cached, but authorization is not.
        verify(shares, times(2)).findByShareToken(token);
        verify(revisions, times(2))
                .findCurrentVersion(diagramId);
        verify(diagrams, times(1)).findById(diagramId);
    }

    @Test
    void differentValidTokensCanShareCachedContent() {
        String firstToken = "view-token";
        String secondToken = "edit-token";

        when(shares.findByShareToken(firstToken))
                .thenReturn(Optional.of(
                        share(firstToken, ShareRole.VIEW)
                ));

        when(shares.findByShareToken(secondToken))
                .thenReturn(Optional.of(
                        share(secondToken, ShareRole.EDIT)
                ));

        stubDiagram();

        SharedDiagramView first =
                service.getDiagramByShareToken(firstToken);

        SharedDiagramView second =
                service.getDiagramByShareToken(secondToken);

        assertEquals(ShareRole.VIEW, first.getRole());
        assertEquals(ShareRole.EDIT, second.getRole());
        assertEquals(first.getDiagramId(), second.getDiagramId());

        // Same diagram/version: only one full DB read.
        verify(diagrams, times(1)).findById(diagramId);
        verify(revisions, times(2))
                .findCurrentVersion(diagramId);
    }

    @Test
    void blankTokenMustBeRejectedBeforeCacheAccess() {
        assertThrows(
                NotFoundException.class,
                () -> service.getDiagramByShareToken("  ")
        );

        verifyNoInteractions(shares, diagrams, revisions);
    }
}
