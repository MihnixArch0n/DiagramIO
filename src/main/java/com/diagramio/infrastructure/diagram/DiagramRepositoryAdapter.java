package com.diagramio.infrastructure.diagram;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramSummary;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class DiagramRepositoryAdapter implements DiagramRepository {

    private final DiagramJpaRepository repository;
    private final DiagramMapper mapper;

    public DiagramRepositoryAdapter(DiagramJpaRepository repository, DiagramMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Ghi ngay (saveAndFlush) để @Version kiểm tra và tăng version trong cùng lời gọi:
     * nếu người khác vừa ghi trước thì trả về 409 thay vì lỗi lúc commit.
     */
    @Override
    public Diagram save(Diagram diagram) {
        try {
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(diagram)));
        } catch (OptimisticLockingFailureException _) {
            Integer currentVersion = repository.findById(diagram.getId()).map(DiagramEntity::getVersion).orElse(null);
            throw new OptimisticLockConflictException(diagram.getId(), diagram.getVersion(), currentVersion);
        }
    }

    @Override
    public Optional<Diagram> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<Diagram> findByOwnerId(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        return findPage(ownerId, diagramType, title, PageRequest.of(page, size))
                .map(mapper::toDomain)
                .getContent();
    }

    @Override
    public List<DiagramSummary> findSummariesByOwner(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        return findSummaryPage(ownerId, diagramType, title, PageRequest.of(page, size))
                .map(this::toSummaryDomain)
                .getContent();
    }

    @Override
    public long countByOwnerId(UUID ownerId, DiagramType diagramType, String title) {
        return findPage(ownerId, diagramType, title, PageRequest.of(0, 1)).getTotalElements();
    }

    @Override
    public List<Diagram> findPublicDiagrams(int page, int size) {
        return repository.findByIsPublicTrue(PageRequest.of(page, size))
                .map(mapper::toDomain)
                .getContent();
    }

    @Override
    public List<DiagramSummary> findPublicDiagramSummaries(int page, int size) {
        return repository.findSummaryByIsPublicTrue(PageRequest.of(page, size))
                .map(this::toSummaryDomain)
                .getContent();
    }

    @Override
    public long countPublicDiagrams() {
        return repository.findByIsPublicTrue(PageRequest.of(0, 1)).getTotalElements();
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    private Page<DiagramEntity> findPage(
            UUID ownerId, DiagramType diagramType, String title, PageRequest pageRequest) {
        boolean hasDiagramType = diagramType != null;
        boolean hasTitle = title != null && !title.isBlank();

        if (hasDiagramType && hasTitle) {
            return repository.findByOwnerIdAndDiagramTypeAndTitleContainingIgnoreCase(
                    ownerId, diagramType.name(), title, pageRequest);
        }
        if (hasDiagramType) {
            return repository.findByOwnerIdAndDiagramType(ownerId, diagramType.name(), pageRequest);
        }
        if (hasTitle) {
            return repository.findByOwnerIdAndTitleContainingIgnoreCase(ownerId, title, pageRequest);
        }
        return repository.findByOwnerId(ownerId, pageRequest);
    }

    private Page<DiagramSummaryRecord> findSummaryPage(
            UUID ownerId, DiagramType diagramType, String title, PageRequest pageRequest) {
        boolean hasDiagramType = diagramType != null;
        boolean hasTitle = title != null && !title.isBlank();

        if (hasDiagramType && hasTitle) {
            return repository.findSummaryByOwnerIdAndDiagramTypeAndTitleContainingIgnoreCase(
                    ownerId, diagramType.name(), title, pageRequest);
        }
        if (hasDiagramType) {
            return repository.findSummaryByOwnerIdAndDiagramType(ownerId, diagramType.name(), pageRequest);
        }
        if (hasTitle) {
            return repository.findSummaryByOwnerIdAndTitleContainingIgnoreCase(ownerId, title, pageRequest);
        }
        return repository.findSummaryByOwnerId(ownerId, pageRequest);
    }

    private DiagramSummary toSummaryDomain(DiagramSummaryRecord record) {
        return new DiagramSummary(
                record.id(),
                record.title(),
                DiagramType.from(record.diagramType()),
                record.version(),
                record.isPublic(),
                record.ownerId(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt());
    }
}
