package com.waibao.pingdian.company.domain;

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
 * 用户上报但工商库未命中的公司候选，只能进入待核验池，不能直接建公司。
 */
@Entity
@Table(name = "company_candidate")
public class CompanyCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "city", length = 64)
    private String city;

    @Column(name = "industry", length = 64)
    private String industry;

    @Column(name = "reporter_user_id")
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CandidateStatus status = CandidateStatus.PENDING;

    @Column(name = "matched_uscc", length = 18)
    private String matchedUscc;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public void setReporterUserId(UUID reporterUserId) {
        this.reporterUserId = reporterUserId;
    }

    public CandidateStatus getStatus() {
        return status;
    }

    public void setStatus(CandidateStatus status) {
        this.status = status;
    }

    public String getMatchedUscc() {
        return matchedUscc;
    }

    public void setMatchedUscc(String matchedUscc) {
        this.matchedUscc = matchedUscc;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
