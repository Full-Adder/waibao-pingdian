package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.domain.AnonymousIdentity;
import com.waibao.pingdian.account.domain.ContributionReason;
import com.waibao.pingdian.account.domain.IdentityStatus;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.repo.AnonymousIdentityRepository;
import com.waibao.pingdian.account.repo.ContributionLedgerRepository;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.account.web.dto.IdentityChangeResult;
import com.waibao.pingdian.account.web.dto.IdentityDetail;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 匿名身份查询与更换：60 贡献值 + 90 天冷却 + 自然年最多 2 次。
 * 历史身份只置 RETIRED，不迁移历史点评（Task 7 依赖快照）。
 */
@Service
public class IdentityService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentUserService currentUserService;
    private final UserAccountRepository userAccountRepository;
    private final AnonymousIdentityRepository identityRepository;
    private final ContributionLedgerRepository ledgerRepository;
    private final AnonymousAliasGenerator aliasGenerator;
    private final ContributionService contributionService;
    private final int changeCost;
    private final int cooldownDays;
    private final int changePerYear;

    public IdentityService(CurrentUserService currentUserService,
                           UserAccountRepository userAccountRepository,
                           AnonymousIdentityRepository identityRepository,
                           ContributionLedgerRepository ledgerRepository,
                           AnonymousAliasGenerator aliasGenerator,
                           ContributionService contributionService,
                           @Value("${identity.change-cost:60}") int changeCost,
                           @Value("${identity.change-cooldown-days:90}") int cooldownDays,
                           @Value("${identity.change-per-year:2}") int changePerYear) {
        this.currentUserService = currentUserService;
        this.userAccountRepository = userAccountRepository;
        this.identityRepository = identityRepository;
        this.ledgerRepository = ledgerRepository;
        this.aliasGenerator = aliasGenerator;
        this.contributionService = contributionService;
        this.changeCost = changeCost;
        this.cooldownDays = cooldownDays;
        this.changePerYear = changePerYear;
    }

    public IdentityDetail currentIdentity() {
        UserAccount user = currentUserService.getUser();
        AnonymousIdentity identity = activeIdentity(user.getId());
        long changesThisYear = changesThisYear(user.getId());
        Instant nextAllowedAt = identity.getLastChangedAt() == null
                ? null
                : identity.getLastChangedAt().plusSeconds(cooldownDays * 86400L);
        long remainingCooldownDays = remainingDays(nextAllowedAt);
        int insufficientPoints = Math.max(0, changeCost - user.getContributionScore());
        long remainingChanges = Math.max(0, changePerYear - changesThisYear);
        boolean canChange = insufficientPoints == 0 && remainingCooldownDays == 0 && remainingChanges > 0;
        return new IdentityDetail(identity.getAlias(), identity.getStatus(), identity.getChangeCount(),
                changeCost, cooldownDays, nextAllowedAt, insufficientPoints,
                remainingCooldownDays, remainingChanges, canChange);
    }

    @Transactional
    public IdentityChangeResult changeIdentity() {
        UserAccount user = currentUserService.getUser();
        AnonymousIdentity current = activeIdentity(user.getId());
        long changesThisYear = changesThisYear(user.getId());

        if (user.getContributionScore() < changeCost) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "贡献值不足，还差 " + (changeCost - user.getContributionScore()) + " 贡献值");
        }
        if (current.getLastChangedAt() != null) {
            Instant nextAllowedAt = current.getLastChangedAt().plusSeconds(cooldownDays * 86400L);
            long remaining = remainingDays(nextAllowedAt);
            if (remaining > 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "距离下次更换还需 " + remaining + " 天");
            }
        }
        if (changesThisYear >= changePerYear) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "本自然年更换次数已用完（上限 " + changePerYear + " 次）");
        }

        String newAlias = aliasGenerator.generate();
        AnonymousIdentity fresh = new AnonymousIdentity();
        fresh.setUserId(user.getId());
        fresh.setAlias(newAlias);
        fresh.setStatus(IdentityStatus.ACTIVE);
        fresh.setChangeCount(current.getChangeCount() + 1);
        fresh.setLastChangedAt(Instant.now());
        identityRepository.save(fresh);

        current.setStatus(IdentityStatus.RETIRED);
        identityRepository.save(current);

        contributionService.award(user.getId(), ContributionReason.IDENTITY_CHANGE,
                "IDENTITY", fresh.getId().toString(), -changeCost);

        long used = changesThisYear + 1;
        long remainingChanges = Math.max(0, changePerYear - used);
        Instant nextAllowedAt = fresh.getLastChangedAt().plusSeconds(cooldownDays * 86400L);
        IdentityDetail detail = new IdentityDetail(fresh.getAlias(), fresh.getStatus(),
                fresh.getChangeCount(), changeCost, cooldownDays, nextAllowedAt,
                0, cooldownDays, remainingChanges, false);
        return new IdentityChangeResult(detail, remainingChanges);
    }

    private AnonymousIdentity activeIdentity(UUID userId) {
        return identityRepository.findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId, IdentityStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "匿名身份不存在"));
    }

    private long changesThisYear(UUID userId) {
        Instant startOfYear = LocalDate.now(BUSINESS_ZONE).withDayOfYear(1)
                .atStartOfDay(BUSINESS_ZONE).toInstant();
        return ledgerRepository.countByUserIdAndReasonAndCreatedAtGreaterThanEqual(
                userId, ContributionReason.IDENTITY_CHANGE.name(), startOfYear);
    }

    private long remainingDays(Instant nextAllowedAt) {
        if (nextAllowedAt == null) {
            return 0;
        }
        long seconds = Duration.between(Instant.now(), nextAllowedAt).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (seconds + 86399) / 86400;
    }
}
