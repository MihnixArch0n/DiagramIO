package com.diagramio.infrastructure.diagram;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DiagramJpaRepository extends JpaRepository<DiagramEntity, UUID> {

    Page<DiagramEntity> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<DiagramEntity> findByOwnerIdAndDiagramType(
            UUID ownerId, String diagramType, Pageable pageable);

    Page<DiagramEntity> findByOwnerIdAndTitleContainingIgnoreCase(
            UUID ownerId, String title, Pageable pageable);

    Page<DiagramEntity> findByOwnerIdAndDiagramTypeAndTitleContainingIgnoreCase(
            UUID ownerId, String diagramType, String title,
            Pageable pageable);

    Page<DiagramEntity> findByIsPublicTrue(Pageable pageable);

    Page<DiagramSummaryRecord> findSummaryByOwnerId(UUID ownerId, Pageable pageable);

    Page<DiagramSummaryRecord> findSummaryByOwnerIdAndDiagramType(
            UUID ownerId, String diagramType, Pageable pageable);

    Page<DiagramSummaryRecord> findSummaryByOwnerIdAndTitleContainingIgnoreCase(
            UUID ownerId, String title, Pageable pageable);

    Page<DiagramSummaryRecord> findSummaryByOwnerIdAndDiagramTypeAndTitleContainingIgnoreCase(
            UUID ownerId, String diagramType, String title,
            Pageable pageable);

    Page<DiagramSummaryRecord> findSummaryByIsPublicTrue(Pageable pageable);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE DiagramEntity d SET d.storageKey = :storageKey, d.data = NULL WHERE d.id = :id")
    void updateStorageKey(@Param("id") UUID id, @Param("storageKey") String storageKey);
}
