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
 * 企业投诉工单。V1 冻结字段 + V2 扩展的举证分级/复核/裁定字段。
 */
@Entity
@Table(name = "complaint")
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "review_id", nullable = false, unique = true)
    private UUID reviewId;

    @Column(name = "claimant_uscc", nullable = false, length = 18)
    private String claimantUscc;

    @Column(name = "claimant_contact_cipher", nullable = false)
    private String claimantContactCipher;

    @Column(name = "license_file_key", columnDefinition = "text")
    private String licenseFileKey;

    @Column(name = "letter_file_key", columnDefinition = "text")
    private String letterFileKey;

    @Column(name = "subject_verified", nullable = false)
    private boolean subjectVerified = false;

    @Column(name = "type", nullable = false, length = 40)
    private String type = "CONTENT_DISPUTE";

    @Column(name = "amount_flag", nullable = false)
    private boolean amountFlag = false;

    @Column(name = "safety_flag", nullable = false)
    private boolean safetyFlag = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ComplaintStatus status = ComplaintStatus.PENDING_VERIFY;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "enterprise_evidence_note", columnDefinition = "text")
    private String enterpriseEvidenceNote;

    @Column(name = "user_evidence_note", columnDefinition = "text")
    private String userEvidenceNote;

    @Column(name = "resolution", length = 30)
    private String resolution;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dual_review_required", nullable = false)
    private boolean dualReviewRequired = false;

    @Column(name = "dual_review_by")
    private UUID dualReviewBy;

    @Column(name = "dual_review_at")
    private Instant dualReviewAt;

    @Column(name = "enterprise_grade", length = 1)
    private String enterpriseGrade;

    @Column(name = "user_grade", length = 1)
    private String userGrade;

    @Column(name = "ruling_reason", columnDefinition = "text")
    private String rulingReason;

    @Column(name = "cited_materials", columnDefinition = "text")
    private String citedMaterials;

    @Column(name = "rule_clause", length = 200)
    private String ruleClause;

    @Column(name = "proposed_resolution", length = 30)
    private String proposedResolution;

    @Column(name = "proposed_by")
    private UUID proposedBy;

    @Column(name = "proposed_at")
    private Instant proposedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public void setReviewId(UUID reviewId) {
        this.reviewId = reviewId;
    }

    public String getClaimantUscc() {
        return claimantUscc;
    }

    public void setClaimantUscc(String claimantUscc) {
        this.claimantUscc = claimantUscc;
    }

    public String getClaimantContactCipher() {
        return claimantContactCipher;
    }

    public void setClaimantContactCipher(String claimantContactCipher) {
        this.claimantContactCipher = claimantContactCipher;
    }

    public String getLicenseFileKey() {
        return licenseFileKey;
    }

    public void setLicenseFileKey(String licenseFileKey) {
        this.licenseFileKey = licenseFileKey;
    }

    public String getLetterFileKey() {
        return letterFileKey;
    }

    public void setLetterFileKey(String letterFileKey) {
        this.letterFileKey = letterFileKey;
    }

    public boolean isSubjectVerified() {
        return subjectVerified;
    }

    public void setSubjectVerified(boolean subjectVerified) {
        this.subjectVerified = subjectVerified;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isAmountFlag() {
        return amountFlag;
    }

    public void setAmountFlag(boolean amountFlag) {
        this.amountFlag = amountFlag;
    }

    public boolean isSafetyFlag() {
        return safetyFlag;
    }

    public void setSafetyFlag(boolean safetyFlag) {
        this.safetyFlag = safetyFlag;
    }

    public ComplaintStatus getStatus() {
        return status;
    }

    public void setStatus(ComplaintStatus status) {
        this.status = status;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public String getEnterpriseEvidenceNote() {
        return enterpriseEvidenceNote;
    }

    public void setEnterpriseEvidenceNote(String enterpriseEvidenceNote) {
        this.enterpriseEvidenceNote = enterpriseEvidenceNote;
    }

    public String getUserEvidenceNote() {
        return userEvidenceNote;
    }

    public void setUserEvidenceNote(String userEvidenceNote) {
        this.userEvidenceNote = userEvidenceNote;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(UUID resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isDualReviewRequired() {
        return dualReviewRequired;
    }

    public void setDualReviewRequired(boolean dualReviewRequired) {
        this.dualReviewRequired = dualReviewRequired;
    }

    public UUID getDualReviewBy() {
        return dualReviewBy;
    }

    public void setDualReviewBy(UUID dualReviewBy) {
        this.dualReviewBy = dualReviewBy;
    }

    public Instant getDualReviewAt() {
        return dualReviewAt;
    }

    public void setDualReviewAt(Instant dualReviewAt) {
        this.dualReviewAt = dualReviewAt;
    }

    public String getEnterpriseGrade() {
        return enterpriseGrade;
    }

    public void setEnterpriseGrade(String enterpriseGrade) {
        this.enterpriseGrade = enterpriseGrade;
    }

    public String getUserGrade() {
        return userGrade;
    }

    public void setUserGrade(String userGrade) {
        this.userGrade = userGrade;
    }

    public String getRulingReason() {
        return rulingReason;
    }

    public void setRulingReason(String rulingReason) {
        this.rulingReason = rulingReason;
    }

    public String getCitedMaterials() {
        return citedMaterials;
    }

    public void setCitedMaterials(String citedMaterials) {
        this.citedMaterials = citedMaterials;
    }

    public String getRuleClause() {
        return ruleClause;
    }

    public void setRuleClause(String ruleClause) {
        this.ruleClause = ruleClause;
    }

    public String getProposedResolution() {
        return proposedResolution;
    }

    public void setProposedResolution(String proposedResolution) {
        this.proposedResolution = proposedResolution;
    }

    public UUID getProposedBy() {
        return proposedBy;
    }

    public void setProposedBy(UUID proposedBy) {
        this.proposedBy = proposedBy;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public void setProposedAt(Instant proposedAt) {
        this.proposedAt = proposedAt;
    }
}
