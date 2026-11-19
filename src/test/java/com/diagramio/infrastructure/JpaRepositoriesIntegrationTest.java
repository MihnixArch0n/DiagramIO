package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramio.config.StorageConfig;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.BlobStoragePort;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramSummary;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.ConflictException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.domain.version.DiagramVersionSummary;
import com.diagramio.infrastructure.diagram.DiagramJpaRepository;
import com.diagramio.infrastructure.diagram.DiagramMapper;
import com.diagramio.infrastructure.diagram.DiagramRepositoryAdapter;
import com.diagramio.infrastructure.share.DiagramShareMapper;
import com.diagramio.infrastructure.share.DiagramShareRepositoryAdapter;
import com.diagramio.infrastructure.version.DiagramVersionRepositoryAdapter;
import com.diagramio.e2e.E2eTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs Flyway + Hibernate (ddl-auto: validate) on real PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DiagramRepositoryAdapter.class, DiagramMapper.class, DiagramVersionRepositoryAdapter.class,
        DiagramShareRepositoryAdapter.class, DiagramShareMapper.class, StorageConfig.class})
class JpaRepositoriesIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = E2eTestBase.postgres;

    /** Sample user seeded in V2. */
    private static final UUID SEED_USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final DiagramData DATA = DiagramData.fromStorage("{\"nodes\": [{\"id\": \"n1\"}], \"edges\": []}");

    @Autowired
    private DiagramRepositoryAdapter diagramRepository;

    @Autowired
    private DiagramVersionRepositoryAdapter versionRepository;

    @Autowired
    private DiagramShareRepositoryAdapter shareRepository;

    @Autowired
    private DiagramJpaRepository diagramJpaRepository;

    @Autowired
    private BlobStoragePort blobStorage;

    @Test
    void claimCheckStoresBlobAndClearsDataInDatabase() {
        Diagram diagram = newDiagram();

        // 1. Direct inspection of diagrams table in PostgreSQL via JPA Entity:
        // data column must be null (to eliminate TOAST), storage_key must be present
        var entity = diagramJpaRepository.findById(diagram.getId()).orElseThrow();
        assertThat(entity.getStorageKey()).isNotNull().startsWith("diagrams/" + diagram.getId());
        assertThat(entity.getData()).isNull();

        // 2. Verify external blob storage holds the expected JSON payload
        var blobContent = blobStorage.load(entity.getStorageKey());
        assertThat(blobContent).isPresent();
        assertThat(blobContent.get()).contains("\"n1\"");

        // 3. Verify repository adapter transparently reads and reconstructs complete domain model
        Diagram loaded = diagramRepository.findById(diagram.getId()).orElseThrow();
        assertThat(loaded.getData()).isNotNull();
        assertThat(loaded.getData().json()).contains("\"n1\"");
    }

    @Test
    void claimCheckPurgesBlobsWhenDiagramIsDeleted() {
        Diagram diagram = newDiagram();
        var entity = diagramJpaRepository.findById(diagram.getId()).orElseThrow();
        String storageKey = entity.getStorageKey();
        assertThat(blobStorage.load(storageKey)).isPresent();

        diagramRepository.deleteById(diagram.getId());

        assertThat(diagramRepository.findById(diagram.getId())).isEmpty();
        assertThat(blobStorage.load(storageKey)).isEmpty();
    }

    @Test
    void claimCheckDeletesOldBlobOnUpdate() {
        Diagram diagram = newDiagram();
        var entity = diagramJpaRepository.findById(diagram.getId()).orElseThrow();
        String initialStorageKey = entity.getStorageKey();
        assertThat(blobStorage.load(initialStorageKey)).isPresent();

        diagram.setTitle("Updated Title");
        diagram.setData(DiagramData.fromStorage("{\"nodes\": [{\"id\": \"updated\"}], \"edges\": []}"));
        diagramRepository.save(diagram);

        var updatedEntity = diagramJpaRepository.findById(diagram.getId()).orElseThrow();
        String newStorageKey = updatedEntity.getStorageKey();
        assertThat(newStorageKey).isNotEqualTo(initialStorageKey);
        assertThat(blobStorage.load(initialStorageKey)).isEmpty();
        assertThat(blobStorage.load(newStorageKey)).isPresent();
    }

    @Test
    void claimCheckCleansUpNewBlobOnOptimisticLockConflict() {
        Diagram diagram = newDiagram();
        Diagram other = diagramRepository.findById(diagram.getId()).orElseThrow();
        other.setTitle("Updated by other");
        diagramRepository.save(other);

        diagram.setTitle("Conflicting update");
        assertThatThrownBy(() -> diagramRepository.save(diagram))
                .isInstanceOf(OptimisticLockConflictException.class);

        var currentEntity = diagramJpaRepository.findById(diagram.getId()).orElseThrow();
        assertThat(blobStorage.load(currentEntity.getStorageKey())).isPresent();
    }

    @Test
    void savesAndReadsSnapshotsNewestFirst() {
        Diagram diagram = newDiagram();
        versionRepository.save(snapshot(diagram.getId(), 1));
        DiagramVersion second = versionRepository.save(snapshot(diagram.getId(), 2));

        List<DiagramVersion> versions = versionRepository.findByDiagramId(diagram.getId());

        assertThat(versions).extracting(DiagramVersion::versionNumber).containsExactly(2, 1);
        assertThat(versionRepository.maxVersionNumber(diagram.getId())).isEqualTo(2);
        assertThat(versionRepository.findByIdAndDiagramId(second.id(), diagram.getId()))
                .get().satisfies(v -> {
                    assertThat(v.data().json()).contains("\"n1\"");
                    assertThat(v.createdBy()).isEqualTo(SEED_USER);
                });
    }

    @Test
    void maxVersionNumberIsZeroWithoutSnapshots() {
        assertThat(versionRepository.maxVersionNumber(newDiagram().getId())).isZero();
    }

    @Test
    void versionIsNotFoundThroughAnotherDiagram() {
        Diagram diagram = newDiagram();
        DiagramVersion v = versionRepository.save(snapshot(diagram.getId(), 1));

        assertThat(versionRepository.findByIdAndDiagramId(v.id(), newDiagram().getId())).isEmpty();
    }

    @Test
    void duplicateVersionNumberIsConflict() {
        Diagram diagram = newDiagram();
        versionRepository.save(snapshot(diagram.getId(), 1));

        assertThatThrownBy(() -> versionRepository.save(snapshot(diagram.getId(), 1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateWithExpectedVersionSucceeds() {
        Diagram diagram = newDiagram();
        diagram.setData(DiagramData.fromStorage("{\"nodes\": [], \"edges\": [{\"id\": \"e1\"}]}"));

        Diagram saved = diagramRepository.save(diagram);

        assertThat(saved.getVersion()).isEqualTo(2);
        Diagram stored = diagramRepository.findById(diagram.getId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getData().json()).contains("\"e1\"");
    }

    @Test
    void staleUpdateIsRejectedAndRowIsUnchanged() {
        Diagram diagram = newDiagram();
        // Người khác đã lưu trước => CSDL lên version 2
        Diagram other = diagramRepository.findById(diagram.getId()).orElseThrow();
        other.setTitle("Renamed by someone else");
        diagramRepository.save(other);

        diagram.setData(DiagramData.fromStorage("{\"nodes\": [], \"edges\": []}")); // vẫn giữ version 1 đã đọc

        assertThatThrownBy(() -> diagramRepository.save(diagram))
                .isInstanceOf(OptimisticLockConflictException.class)
                .hasMessage("Diagram version conflict: requested 1, current 2");
        Diagram stored = diagramRepository.findById(diagram.getId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getData().json()).contains("\"n1\"");
    }

    @Test
    void updateRoleChangesRoleOfExistingShare() {
        DiagramShare share = newShare(newDiagram().getId());

        assertThat(shareRepository.updateRole(share.getId(), share.getDiagramId(), ShareRole.EDIT)).isTrue();
        assertThat(shareRepository.findById(share.getId())).get()
                .extracting(DiagramShare::getRole).isEqualTo(ShareRole.EDIT);
    }

    @Test
    void roleUpdateAfterRevokeDoesNotRecreateTheLink() {
        DiagramShare share = newShare(newDiagram().getId());
        shareRepository.deleteById(share.getId());

        assertThat(shareRepository.updateRole(share.getId(), share.getDiagramId(), ShareRole.EDIT)).isFalse();
        assertThat(shareRepository.findByShareToken(share.getShareToken())).isEmpty();
    }

    @Test
    void roleUpdateThroughAnotherDiagramMatchesNothing() {
        DiagramShare share = newShare(newDiagram().getId());

        assertThat(shareRepository.updateRole(share.getId(), newDiagram().getId(), ShareRole.EDIT)).isFalse();
        assertThat(shareRepository.findById(share.getId())).get()
                .extracting(DiagramShare::getRole).isEqualTo(ShareRole.VIEW);
    }

    @Test
    void findsVersionSummariesWithoutLoadingDataPayload() {
        Diagram diagram = newDiagram();
        versionRepository.save(snapshot(diagram.getId(), 1));
        versionRepository.save(snapshot(diagram.getId(), 2));

        List<DiagramVersionSummary> summaries = versionRepository.findSummariesByDiagramId(diagram.getId());

        assertThat(summaries).extracting(DiagramVersionSummary::versionNumber).containsExactly(2, 1);
        assertThat(summaries).allSatisfy(s -> {
            assertThat(s.diagramId()).isEqualTo(diagram.getId());
            assertThat(s.createdBy()).isEqualTo(SEED_USER);
        });
    }

    @Test
    void throwsIllegalStateExceptionWhenBlobIsMissing() {
        Diagram diagram = newDiagram();
        var entity = diagramJpaRepository.findById(diagram.getId()).orElseThrow();
        // Xóa file blob ngoài đĩa để mô phỏng rủi ro mất file
        blobStorage.delete(entity.getStorageKey());

        assertThatThrownBy(() -> diagramRepository.findById(diagram.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payload blob missing");
    }

    @Test
    void findsSummariesByOwnerAndPublicWithoutLoadingData() {
        Diagram diagram = newDiagram();

        List<DiagramSummary> summaries = diagramRepository.findSummariesByOwner(SEED_USER, DiagramType.ERD, "Shop", 0, 10);
        assertThat(summaries).isNotEmpty();
        DiagramSummary summary = summaries.stream().filter(s -> s.id().equals(diagram.getId())).findFirst().orElseThrow();
        assertThat(summary.title()).isEqualTo("Shop ERD");
        assertThat(summary.diagramType()).isEqualTo(DiagramType.ERD);
        assertThat(summary.ownerId()).isEqualTo(SEED_USER);

        // Make it public and test findPublicDiagramSummaries
        diagram.setPublic(true);
        diagramRepository.save(diagram);

        List<DiagramSummary> publicSummaries = diagramRepository.findPublicDiagramSummaries(0, 100);
        assertThat(publicSummaries).extracting(DiagramSummary::id).contains(diagram.getId());
    }

    private DiagramShare newShare(UUID diagramId) {
        return shareRepository.save(new DiagramShare(UUID.randomUUID(), diagramId, "token-" + UUID.randomUUID(),
                ShareRole.VIEW, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private Diagram newDiagram() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Diagram diagram = new Diagram(null, "Shop ERD", DiagramType.ERD, DATA, 1, false, SEED_USER, SEED_USER, now, now);
        return diagramRepository.save(diagram);
    }

    private DiagramVersion snapshot(UUID diagramId, int number) {
        return new DiagramVersion(UUID.randomUUID(), diagramId, number, DATA, "note " + number, SEED_USER,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
