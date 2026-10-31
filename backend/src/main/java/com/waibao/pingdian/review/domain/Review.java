package com.waibao.pingdian.review.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.verification.domain.VerificationBadge;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 点评主表。字段与 V1__init.sql 冻结结构一一对应；period/tags 存 tag_json。
 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "uscc", nullable = false, length = 18)
    private String uscc;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "identity_id", nullable = false)
    private UUID identityId;

    @Column(name = "verification_id")
    private UUID verificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_badge", length = 20)
    private VerificationBadge verificationBadge;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Column(name = "content", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tag_json", columnDefinition = "jsonb")
    private JsonNode tagJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReviewStatus status = ReviewStatus.DRAFT;

    @Column(name = "ai_confidence", precision = 4, scale = 3)
    private BigDecimal aiConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_flags", columnDefinition = "jsonb")
    private JsonNode aiFlags;

    @Column(name = "ai_rules_version", length = 32)
    private String aiRulesVersion;

    @Column(name = "edit_count", nullable = false)
    private int editCount = 0;

    @Column(name = "edit_deadline")
    private Instant editDeadline;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "dispute_deadline")
    private Instant disputeDeadline;

    @Column(name = "ever_disputed", nullable = false)
    private boolean everDisputed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUscc() {
        return uscc;
    }

    public void setUscc(String uscc) {
        this.uscc = uscc;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getIdentityId() {
        return identityId;
    }

    public void setIdentityId(UUID identityId) {
        this.identityId = identityId;
    }

    public UUID getVerificationId() {
        return verificationId;
    }

    public void setVerificationId(UUID verificationId) {
        this.verificationId = verificationId;
    }

    public VerificationBadge getVerificationBadge() {
        return verificationBadge;
    }

    public void setVerificationBadge(VerificationBadge verificationBadge) {
        this.verificationBadge = verificationBadge;
    }

    public int getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(int totalScore) {
        this.totalScore = totalScore;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public JsonNode getTagJson() {
        return tagJson;
    }

    public void setTagJson(JsonNode tagJson) {
        this.tagJson = tagJson;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewStatus status) {
        this.status = status;
    }

    public BigDecimal getAiConfidence() {
        return aiConfidence;
    }

    public void setAiConfidence(BigDecimal aiConfidence) {
        this.aiConfidence = aiConfidence;
    }

    public JsonNode getAiFlags() {
        return aiFlags;
    }

    public void setAiFlags(JsonNode aiFlags) {
        this.aiFlags = aiFlags;
    }

    public String getAiRulesVersion() {
        return aiRulesVersion;
    }

    public void setAiRulesVersion(String aiRulesVersion) {
        this.aiRulesVersion = aiRulesVersion;
    }

    public int getEditCount() {
        return editCount;
    }

    public void setEditCount(int editCount) {
        this.editCount = editCount;
    }

    public Instant getEditDeadline() {
        return editDeadline;
    }

    public void setEditDeadline(Instant editDeadline) {
        this.editDeadline = editDeadline;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(Instant withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }

    public Instant getDisputeDeadline() {
        return disputeDeadline;
    }

    public void setDisputeDeadline(Instant disputeDeadline) {
        this.disputeDeadline = disputeDeadline;
    }

    public boolean isEverDisputed() {
        return everDisputed;
    }

    public void setEverDisputed(boolean everDisputed) {
        this.everDisputed = everDisputed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
