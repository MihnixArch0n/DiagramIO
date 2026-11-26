package com.diagramio.infrastructure.diagram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.diagramio.domain.diagram.BlobStoragePort;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramReadCache;
import com.diagramio.domain.diagram.DiagramType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DiagramRepositoryAdapterCacheTest {

    private static final String DATA =
            "{\"nodes\":[],\"edges\":[]}";

    private DiagramJpaRepository repository;
    private DiagramMapper mapper;
    private BlobStoragePort blobStorage;
    private DiagramReadCache readCache;

    private DiagramRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(DiagramJpaRepository.class);
        mapper = mock(DiagramMapper.class);
        blobStorage = mock(BlobStoragePort.class);
        readCache = mock(DiagramReadCache.class);

        adapter = new DiagramRepositoryAdapter(
                repository,
                mapper,
                blobStorage,
                readCache
        );
    }

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void updateInvalidatesCacheImmediatelyWithoutOuterTransaction() {
        UUID id = UUID.randomUUID();
        Diagram diagram = stubUpdate(id);

        adapter.save(diagram);

        verify(readCache).invalidateDiagram(id);
    }

    @Test
    void updateInvalidatesCacheOnlyAfterCommit() {
        UUID id = UUID.randomUUID();
        Diagram diagram = stubUpdate(id);

        beginTransaction();

        adapter.save(diagram);

        verify(readCache, never()).invalidateDiagram(id);

        commitTransaction();

        verify(readCache).invalidateDiagram(id);
    }

    @Test
    void deleteInvalidatesCacheOnlyAfterCommit() {
        UUID id = UUID.randomUUID();

        beginTransaction();

        adapter.deleteById(id);

        verify(repository).deleteById(id);
        verify(readCache, never()).invalidateDiagram(id);

        commitTransaction();

        verify(readCache).invalidateDiagram(id);
        verify(blobStorage).deleteAllPrefix("diagrams/" + id);
    }

    @Test
    void rollbackDoesNotInvalidateCache() {
        UUID id = UUID.randomUUID();
        Diagram diagram = stubUpdate(id);

        beginTransaction();

        adapter.save(diagram);

        verify(readCache, never()).invalidateDiagram(id);

        rollbackTransaction();

        verify(readCache, never()).invalidateDiagram(id);
    }

    private Diagram stubUpdate(UUID id) {
        Diagram diagram = new Diagram(
                id,
                "Updated diagram",
                DiagramType.UML,
                DiagramData.of(DATA),
                1,
                false,
                UUID.randomUUID(),
                null,
                Instant.now(),
                Instant.now()
        );

        DiagramEntity existing = new DiagramEntity();
        existing.setId(id);
        existing.setStorageKey(
                "diagrams/" + id + "/old.json"
        );

        DiagramEntity saved = new DiagramEntity();
        saved.setId(id);

        when(repository.findById(id))
                .thenReturn(Optional.of(existing));

        when(mapper.toEntity(eq(diagram), anyString()))
                .thenReturn(saved);

        when(repository.saveAndFlush(any(DiagramEntity.class)))
                .thenReturn(saved);

        when(mapper.toDomain(saved, DATA))
                .thenReturn(diagram);

        return diagram;
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void commitTransaction() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();

        synchronizations.forEach(
                TransactionSynchronization::afterCommit
        );

        finishTransaction(
                synchronizations,
                TransactionSynchronization.STATUS_COMMITTED
        );
    }

    private void rollbackTransaction() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();

        finishTransaction(
                synchronizations,
                TransactionSynchronization.STATUS_ROLLED_BACK
        );
    }

    private void finishTransaction(
            List<TransactionSynchronization> synchronizations,
            int status
    ) {
        synchronizations.forEach(
                sync -> sync.afterCompletion(status)
        );

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}

