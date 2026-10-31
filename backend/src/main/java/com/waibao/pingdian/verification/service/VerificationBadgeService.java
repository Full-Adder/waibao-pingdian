package com.waibao.pingdian.verification.service;

import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.verification.domain.UserVerification;
import com.waibao.pingdian.verification.domain.VerificationBadge;
import com.waibao.pingdian.verification.domain.VerificationStatus;
import com.waibao.pingdian.verification.repo.UserVerificationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * 徽标与发评窗口：
 * - period_end 不早于当前月 → CURRENT（在职中），否则 FORMER（曾在职）；
 * - 曾在职自 period_end 起 12 个月内可发评，超窗需重新提交佐证；
 * - 同一公司多次佐证取最近通过的一条。
 */
@Service
public class VerificationBadgeService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int FORMER_WINDOW_MONTHS = 12;

    private final UserVerificationRepository verificationRepository;

    public VerificationBadgeService(UserVerificationRepository verificationRepository) {
        this.verificationRepository = verificationRepository;
    }

    public Optional<VerificationBadge> badgeFor(UUID userId, String uscc) {
        return latestApproved(userId, uscc).map(this::badgeOf);
    }

    /**
     * 供点评写入时快照 verification_id / 徽标使用。
     */
    public Optional<UserVerification> latestApprovedFor(UUID userId, String uscc) {
        return latestApproved(userId, uscc);
    }

    /**
     * 与 assertCanReview 等价，返回布尔值供业务层组合判断。
     */
    public boolean canReview(UUID userId, String uscc) {
        return isVerifiedForCompany(userId, uscc);
    }

    public VerificationBadge badgeOf(UserVerification verification) {
        YearMonth currentMonth = YearMonth.now(BUSINESS_ZONE);
        YearMonth endMonth = YearMonth.from(verification.getPeriodEnd());
        return !endMonth.isBefore(currentMonth) ? VerificationBadge.CURRENT : VerificationBadge.FORMER;
    }

    public void assertCanReview(UUID userId, String uscc) {
        UserVerification verification = latestApproved(userId, uscc)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN,
                        "请先提交该公司在职佐证并通过审核"));
        if (badgeOf(verification) == VerificationBadge.CURRENT) {
            return;
        }
        LocalDate windowEnd = verification.getPeriodEnd().plusMonths(FORMER_WINDOW_MONTHS);
        if (LocalDate.now(BUSINESS_ZONE).isAfter(windowEnd)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "曾在职已超过 12 个月发评窗口，请重新提交在职佐证");
        }
    }

    public boolean isVerifiedForCompany(UUID userId, String uscc) {
        Optional<UserVerification> verification = latestApproved(userId, uscc);
        if (verification.isEmpty()) {
            return false;
        }
        UserVerification value = verification.get();
        if (badgeOf(value) == VerificationBadge.CURRENT) {
            return true;
        }
        return !LocalDate.now(BUSINESS_ZONE).isAfter(value.getPeriodEnd().plusMonths(FORMER_WINDOW_MONTHS));
    }

    private Optional<UserVerification> latestApproved(UUID userId, String uscc) {
        return verificationRepository
                .findFirstByUserIdAndCompanyUsccAndStatusOrderByPeriodEndDescReviewedAtDesc(
                        userId, uscc, VerificationStatus.APPROVED);
    }
}
