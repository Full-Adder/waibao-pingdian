package com.waibao.pingdian.complaint.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 投诉举证材料。文件仅存私有加密 file_key，读取必须走签名 URL + 工单号审计。
 */
@Entity
@Table(name = "complaint_evidence")
public class ComplaintEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "complaint_id", nullable = false)
    private UUID complaintId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 10)
    private ComplaintParty side;

    @Column(name = "file_key", nullable = false, columnDefinition = "text")
    private String fileKey;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "evidence_level", length = 1)
    private String evidenceLevel;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "material_status", nullable = false, length = 20)
    private String materialStatus = "ACTIVE";

    @Column(name = "material_deleted_at")
    private Instant materialDeletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getComplaintId() {
        return complaintId;
    }

    public void setComplaintId(UUID complaintId) {
        this.complaintId = complaintId;
    }

    public ComplaintParty getSide() {
        return side;
    }

    public void setSide(ComplaintParty side) {
        this.side = side;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getEvidenceLevel() {
        return evidenceLevel;
    }

    public void setEvidenceLevel(String evidenceLevel) {
        this.evidenceLevel = evidenceLevel;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getMaterialStatus() {
        return materialStatus;
    }

    public void setMaterialStatus(String materialStatus) {
        this.materialStatus = materialStatus;
    }

    public Instant getMaterialDeletedAt() {
        return materialDeletedAt;
    }

    public void setMaterialDeletedAt(Instant materialDeletedAt) {
        this.materialDeletedAt = materialDeletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
