package com.waibao.pingdian.complaint.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 投诉黑名单：主体核验造假或反复恶意投诉的企业 USCC。
 */
@Entity
@Table(name = "complaint_blocklist")
public class ComplaintBlocklist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "uscc", nullable = false, length = 18)
    private String uscc;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "evidence_file", columnDefinition = "text")
    private String evidenceFile;

    @Column(name = "until")
    private Instant until;

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

    public String getUscc() {
        return uscc;
    }

    public void setUscc(String uscc) {
        this.uscc = uscc;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getEvidenceFile() {
        return evidenceFile;
    }

    public void setEvidenceFile(String evidenceFile) {
        this.evidenceFile = evidenceFile;
    }

    public Instant getUntil() {
        return until;
    }

    public void setUntil(Instant until) {
        this.until = until;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
