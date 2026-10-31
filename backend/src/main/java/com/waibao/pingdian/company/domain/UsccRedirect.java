package com.waibao.pingdian.company.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 公司合并后的旧 USCC 永久跳转映射。
 */
@Entity
@Table(name = "uscc_redirect")
public class UsccRedirect {

    @Id
    @Column(name = "old_uscc", length = 18)
    private String oldUscc;

    @Column(name = "new_uscc", nullable = false, length = 18)
    private String newUscc;

    @Column(name = "merged_at", nullable = false)
    private Instant mergedAt;

    @Column(name = "reason")
    private String reason;

    @Column(name = "operator_id")
    private UUID operatorId;

    @PrePersist
    void prePersist() {
        if (mergedAt == null) {
            mergedAt = Instant.now();
        }
    }

    public String getOldUscc() {
        return oldUscc;
    }

    public void setOldUscc(String oldUscc) {
        this.oldUscc = oldUscc;
    }

    public String getNewUscc() {
        return newUscc;
    }

    public void setNewUscc(String newUscc) {
        this.newUscc = newUscc;
    }

    public Instant getMergedAt() {
        return mergedAt;
    }

    public void setMergedAt(Instant mergedAt) {
        this.mergedAt = mergedAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(UUID operatorId) {
        this.operatorId = operatorId;
    }
}
