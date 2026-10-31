package com.waibao.pingdian.review.domain;

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
 * 状态迁移审计（review_audit）。detail 为 JSON 文本，驳回原因也写在这里。
 */
@Entity
@Table(name = "review_audit")
public class ReviewAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "stage", nullable = false, length = 30)
    private String stage;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ReviewActorType actorType;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private ReviewStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30)
    private ReviewStatus toStatus;

    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    @Column(name = "detail")
    private String detail;

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

    public UUID getReviewId() {
        return reviewId;
    }

    public void setReviewId(UUID reviewId) {
        this.reviewId = reviewId;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public ReviewActorType getActorType() {
        return actorType;
    }

    public void setActorType(ReviewActorType actorType) {
        this.actorType = actorType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public ReviewStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(ReviewStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public ReviewStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(ReviewStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
