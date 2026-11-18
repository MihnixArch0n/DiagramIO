package com.diagramio.infrastructure.version;

import com.diagramio.domain.diagram.BlobStoragePort;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.version.DiagramVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table diagram_versions. All columns have updatable = false because snapshots are immutable. */
@Entity
@Table(name = "diagram_versions")
public class DiagramVersionEntity {

    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "diagram_id", nullable = false, updatable = false)
    private UUID diagramId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", updatable = false, columnDefinition = "jsonb")
    private String data;

    @Column(name = "storage_key", updatable = false)
    private String storageKey;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected DiagramVersionEntity() {
        // JPA requires a no-arg constructor
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    static DiagramVersionEntity fromDomain(DiagramVersion version) {
        return fromDomain(version, null);
    }

    static DiagramVersionEntity fromDomain(DiagramVersion version, String storageKey) {
        DiagramVersionEntity entity = new DiagramVersionEntity();
        entity.id = version.id();
        entity.diagramId = version.diagramId();
        entity.versionNumber = version.versionNumber();
        entity.storageKey = storageKey;
        // If storageKey is present, do not store data JSON in the database (frees up TOAST)
        entity.data = (storageKey == null && version.data() != null) ? version.data().json() : null;
        entity.note = version.note();
        entity.createdBy = version.createdBy();
        entity.createdAt = version.createdAt();
        return entity;
    }

    DiagramVersion toDomain() {
        return toDomain(null);
    }

    DiagramVersion toDomain(BlobStoragePort blobStorage) {
        String dataJson = null;
        if (storageKey != null && blobStorage != null) {
            dataJson = blobStorage.load(storageKey).orElse(null);
        }
        if (dataJson == null) {
            dataJson = data;
        }
        return new DiagramVersion(id, diagramId, versionNumber,
                dataJson == null ? null : DiagramData.fromStorage(dataJson),
                note, createdBy, createdAt);
    }
}
