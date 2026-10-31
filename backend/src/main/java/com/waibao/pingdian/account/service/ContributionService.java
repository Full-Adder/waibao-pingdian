package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.domain.ContributionLedger;
import com.waibao.pingdian.account.domain.ContributionReason;
import com.waibao.pingdian.account.repo.ContributionLedgerRepository;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 贡献值发放：contribution_ledger 的唯一约束保证幂等，user_account.contribution_score 原子累加。
 */
@Service
public class ContributionService {

    private final ContributionLedgerRepository ledgerRepository;
    private final UserAccountRepository userAccountRepository;
    private final JdbcTemplate jdbcTemplate;

    public ContributionService(ContributionLedgerRepository ledgerRepository,
                               UserAccountRepository userAccountRepository,
                               JdbcTemplate jdbcTemplate) {
        this.ledgerRepository = ledgerRepository;
        this.userAccountRepository = userAccountRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean award(UUID userId, ContributionReason reason, String refType, String refId, int delta) {
        if (ledgerRepository.existsByUserIdAndReasonAndRefId(userId, reason.name(), refId)) {
            return false;
        }
        ContributionLedger ledger = new ContributionLedger();
        ledger.setUserId(userId);
        ledger.setDelta(delta);
        ledger.setReason(reason.name());
        ledger.setRefType(refType);
        ledger.setRefId(refId);
        ledgerRepository.save(ledger);
        userAccountRepository.addContribution(userId, delta);
        return true;
    }

    @Transactional
    public void awardVerificationApproved(UUID userId, UUID verificationId) {
        award(userId, ContributionReason.VERIFICATION, "VERIFICATION", verificationId.toString(), 50);
    }

    /**
     * 每条过审 +10；若这是该用户第一条 PUBLISHED 评价，再额外 +10。
     * 必须在 review 状态已落库为 PUBLISHED 后调用（Task 7）。
     */
    @Transactional
    public void awardReviewPublished(UUID userId, UUID reviewId) {
        award(userId, ContributionReason.REVIEW, "REVIEW", reviewId.toString(), 10);
        long publishedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review WHERE user_id = ? AND status = 'PUBLISHED'",
                Long.class, userId);
        if (publishedCount == 1) {
            award(userId, ContributionReason.FIRST_REVIEW_BONUS, "REVIEW", reviewId.toString(), 10);
        }
    }

    @Transactional
    public void awardValidReport(UUID userId, UUID reportId) {
        award(userId, ContributionReason.VALID_REPORT, "REPORT", reportId.toString(), 20);
    }
}
