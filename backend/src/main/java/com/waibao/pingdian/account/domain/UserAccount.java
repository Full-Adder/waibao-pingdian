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
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_cipher", nullable = false)
    private String phoneCipher;

    @Column(name = "phone_hash", nullable = false, length = 64, unique = true)
    private String phoneHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "realname_status", nullable = false, length = 20)
    private RealnameStatus realnameStatus = RealnameStatus.UNVERIFIED;

    @Column(name = "realname_verified_at")
    private Instant realnameVerifiedAt;

    @Column(name = "realname_fail_count", nullable = false)
    private int realnameFailCount = 0;

    @Column(name = "realname_locked_until")
    private Instant realnameLockedUntil;

    @Column(name = "id_hash", length = 64)
    private String idHash;

    @Column(name = "id_masked", length = 32)
    private String idMasked;

    @Column(name = "contribution_score", nullable = false)
    private int contributionScore = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "session_jti", length = 64)
    private String sessionJti;

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

    public String getPhoneCipher() {
        return phoneCipher;
    }

    public void setPhoneCipher(String phoneCipher) {
        this.phoneCipher = phoneCipher;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public void setPhoneHash(String phoneHash) {
        this.phoneHash = phoneHash;
    }

    public RealnameStatus getRealnameStatus() {
        return realnameStatus;
    }

    public void setRealnameStatus(RealnameStatus realnameStatus) {
        this.realnameStatus = realnameStatus;
    }

    public Instant getRealnameVerifiedAt() {
        return realnameVerifiedAt;
    }

    public void setRealnameVerifiedAt(Instant realnameVerifiedAt) {
        this.realnameVerifiedAt = realnameVerifiedAt;
    }

    public int getRealnameFailCount() {
        return realnameFailCount;
    }

    public void setRealnameFailCount(int realnameFailCount) {
        this.realnameFailCount = realnameFailCount;
    }

    public Instant getRealnameLockedUntil() {
        return realnameLockedUntil;
    }

    public void setRealnameLockedUntil(Instant realnameLockedUntil) {
        this.realnameLockedUntil = realnameLockedUntil;
    }

    public String getIdHash() {
        return idHash;
    }

    public void setIdHash(String idHash) {
        this.idHash = idHash;
    }

    public String getIdMasked() {
        return idMasked;
    }

    public void setIdMasked(String idMasked) {
        this.idMasked = idMasked;
    }

    public int getContributionScore() {
        return contributionScore;
    }

    public void setContributionScore(int contributionScore) {
        this.contributionScore = contributionScore;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public String getSessionJti() {
        return sessionJti;
    }

    public void setSessionJti(String sessionJti) {
        this.sessionJti = sessionJti;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
