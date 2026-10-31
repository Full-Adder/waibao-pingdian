package com.waibao.pingdian.account.domain;

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

@Entity
@Table(name = "anonymous_identity")
public class AnonymousIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "alias", nullable = false, length = 32, unique = true)
    private String alias;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdentityStatus status = IdentityStatus.ACTIVE;

    @Column(name = "change_count", nullable = false)
    private int changeCount = 0;

    @Column(name = "last_changed_at")
    private Instant lastChangedAt;

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

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public IdentityStatus getStatus() {
        return status;
    }

    public void setStatus(IdentityStatus status) {
        this.status = status;
    }

    public int getChangeCount() {
        return changeCount;
    }

    public void setChangeCount(int changeCount) {
        this.changeCount = changeCount;
    }

    public Instant getLastChangedAt() {
        return lastChangedAt;
    }

    public void setLastChangedAt(Instant lastChangedAt) {
        this.lastChangedAt = lastChangedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
