package com.waibao.pingdian.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.account.domain.AnonymousIdentity;
import com.waibao.pingdian.account.domain.ContributionReason;
import com.waibao.pingdian.account.domain.IdentityStatus;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.domain.UserStatus;
import com.waibao.pingdian.account.repo.AnonymousIdentityRepository;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.account.web.dto.AdminUserDetail;
import com.waibao.pingdian.account.web.dto.AdminUserListItem;
import com.waibao.pingdian.account.web.dto.BanUserRequest;
import com.waibao.pingdian.account.web.dto.ContributionAdjustRequest;
import com.waibao.pingdian.account.web.dto.VerificationSummary;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.security.CryptoService;
import com.waibao.pingdian.verification.domain.UserVerification;
import com.waibao.pingdian.verification.domain.VerificationStatus;
import com.waibao.pingdian.verification.repo.UserVerificationRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 管理端用户查询、封禁与贡献值调整（R17/R6）。所有写操作均写审计。
 */
@Service
public class AdminUserService {

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int MAX_PAGE_SIZE = 100;

    private final UserAccountRepository userAccountRepository;
    private final AnonymousIdentityRepository identityRepository;
    private final UserVerificationRepository verificationRepository;
    private final ReviewRepository reviewRepository;
    private final CryptoService cryptoService;
    private final ContributionService contributionService;
    private final AdminAuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminUserService(UserAccountRepository userAccountRepository,
                            AnonymousIdentityRepository identityRepository,
                            UserVerificationRepository verificationRepository,
                            ReviewRepository reviewRepository,
                            CryptoService cryptoService,
                            ContributionService contributionService,
                            AdminAuditService auditService,
                            ObjectMapper objectMapper) {
        this.userAccountRepository = userAccountRepository;
        this.identityRepository = identityRepository;
        this.verificationRepository = verificationRepository;
        this.reviewRepository = reviewRepository;
        this.cryptoService = cryptoService;
        this.contributionService = contributionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public PageResult<AdminUserListItem> list(String keyword, String rawStatus, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);

        UserStatus status = parseStatus(rawStatus);
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Specification<UserAccount> specification = buildSpecification(normalized, status);

        Page<UserAccount> result = userAccountRepository.findAll(specification, pageable);
        Map<UUID, String> aliases = activeAliases(result.getContent());
        List<AdminUserListItem> items = result.getContent().stream()
                .map(user -> {
                    String maskedPhone = cryptoService.maskedPhone(user.getPhoneCipher());
                    return new AdminUserListItem(
                            user.getId(),
                            maskedPhone,
                            maskedPhone,
                            user.getRealnameStatus(),
                            user.getContributionScore(),
                            user.getStatus(),
                            user.getCreatedAt(),
                            aliases.get(user.getId()),
                            verificationRepository.countByUserIdAndStatus(
                                    user.getId(), VerificationStatus.APPROVED),
                            reviewRepository.countByUserIdAndStatus(user.getId(), ReviewStatus.PUBLISHED));
                })
                .toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    public AdminUserDetail detail(UUID userId) {
        UserAccount user = requireUser(userId);
        List<UserVerification> verifications = verificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<VerificationSummary> summaries = verifications.stream()
                .map(verification -> new VerificationSummary(
                        verification.getId(),
                        verification.getStatus(),
                        verification.getCompanyUscc(),
                        verification.getPeriodStart(),
                        verification.getPeriodEnd(),
                        verification.getProofType(),
                        verification.getReviewedAt()))
                .toList();

        Map<String, Long> reviewCounts = new LinkedHashMap<>();
        for (ReviewStatus status : ReviewStatus.values()) {
            long count = reviewRepository.countByUserIdAndStatus(userId, status);
            if (count > 0) {
                reviewCounts.put(status.name(), count);
            }
        }

        String maskedPhone = cryptoService.maskedPhone(user.getPhoneCipher());
        return new AdminUserDetail(
                user.getId(),
                maskedPhone,
                maskedPhone,
                user.getRealnameStatus(),
                user.getContributionScore(),
                user.getStatus(),
                user.getCreatedAt(),
                activeAliases(List.of(user)).get(user.getId()),
                verificationRepository.countByUserIdAndStatus(userId, VerificationStatus.APPROVED),
                reviewRepository.countByUserIdAndStatus(userId, ReviewStatus.PUBLISHED),
                summaries,
                reviewCounts);
    }

    @Transactional
    public void ban(UUID userId, BanUserRequest request, AdminUser admin, String ip) {
        requireSuperAdmin(admin);
        UserAccount user = requireUser(userId);
        boolean banned = Boolean.TRUE.equals(request.ban());
        UserStatus target = banned ? UserStatus.BANNED : UserStatus.ACTIVE;
        String action = banned ? "USER_BAN" : "USER_UNBAN";
        String reason = request.reason().trim();

        if (user.getStatus() != target) {
            user.setStatus(target);
            if (banned) {
                user.setSessionJti(null);
            }
            userAccountRepository.save(user);
        }

        auditService.record(admin.getId(), admin.getRole(), action, "USER",
                user.getId().toString(), null, reason, user.getId().toString(), ip);
    }

    @Transactional
    public void adjustContribution(UUID userId, ContributionAdjustRequest request,
                                   AdminUser admin, String ip) {
        requireSuperAdmin(admin);
        UserAccount user = requireUser(userId);
        String reason = request.reason().trim();
        UUID refId = parseRefId(request.refId());

        int delta = request.delta();
        int applied = delta;
        if (applied < 0) {
            applied = Math.max(applied, -user.getContributionScore());
        }
        boolean appliedOnce = contributionService.award(
                userId, ContributionReason.ADMIN_ADJUST, "USER", refId.toString(), applied);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("delta", delta);
        detail.put("appliedDelta", appliedOnce ? applied : 0);
        detail.put("reason", reason);
        detail.put("refId", refId.toString());
        auditService.record(admin.getId(), admin.getRole(), "CONTRIBUTION_ADJUST", "USER",
                user.getId().toString(), "ADMIN_ADJUST", json(detail), user.getId().toString(), ip);
    }

    private Specification<UserAccount> buildSpecification(String keyword, UserStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (keyword != null) {
                if (PHONE.matcher(keyword).matches()) {
                    predicates.add(cb.equal(root.get("phoneHash"), cryptoService.hmacSha256Hex(keyword)));
                } else {
                    List<UUID> userIds = identityRepository.findUserIdsByAliasContaining(keyword);
                    if (userIds.isEmpty()) {
                        predicates.add(cb.disjunction());
                    } else {
                        predicates.add(root.get("id").in(userIds));
                    }
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Map<UUID, String> activeAliases(List<UserAccount> users) {
        if (users.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = users.stream().map(UserAccount::getId).toList();
        return identityRepository.findByUserIdInAndStatus(ids, IdentityStatus.ACTIVE).stream()
                .collect(Collectors.toMap(AnonymousIdentity::getUserId, AnonymousIdentity::getAlias,
                        (first, second) -> first));
    }

    private UserAccount requireUser(UUID userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private void requireSuperAdmin(AdminUser admin) {
        if (admin.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅超级管理员可执行该操作");
        }
    }

    private UserStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户状态不正确");
        }
    }

    private UUID parseRefId(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "refId 格式不正确");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
