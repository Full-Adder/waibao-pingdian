package com.waibao.pingdian.verification.domain;

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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "user_verification")
public class UserVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "proof_type", nullable = false, length = 30)
    private ProofType proofType;

    @Column(name = "file_key")
    private String fileKey;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "masked_file_key")
    private String maskedFileKey;

    @Column(name = "company_uscc", nullable = false, length = 18)
    private String companyUscc;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VerificationStatus status = VerificationStatus.PENDING;

    @Column(name = "reject_reason_code", length = 40)
    private String rejectReasonCode;

    @Column(name = "reject_note")
    private String rejectNote;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_status", nullable = false, length = 20)
    private MaterialStatus materialStatus = MaterialStatus.ACTIVE;

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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public ProofType getProofType() {
        return proofType;
    }

    public void setProofType(ProofType proofType) {
        this.proofType = proofType;
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

    public String getMaskedFileKey() {
        return maskedFileKey;
    }

    public void setMaskedFileKey(String maskedFileKey) {
        this.maskedFileKey = maskedFileKey;
    }

    public String getCompanyUscc() {
        return companyUscc;
    }

    public void setCompanyUscc(String companyUscc) {
        this.companyUscc = companyUscc;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public void setStatus(VerificationStatus status) {
        this.status = status;
    }

    public String getRejectReasonCode() {
        return rejectReasonCode;
    }

    public void setRejectReasonCode(String rejectReasonCode) {
        this.rejectReasonCode = rejectReasonCode;
    }

    public String getRejectNote() {
        return rejectNote;
    }

    public void setRejectNote(String rejectNote) {
        this.rejectNote = rejectNote;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(UUID reviewerId) {
        this.reviewerId = reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public MaterialStatus getMaterialStatus() {
        return materialStatus;
    }

    public void setMaterialStatus(MaterialStatus materialStatus) {
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
