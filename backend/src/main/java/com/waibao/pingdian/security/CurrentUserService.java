package com.waibao.pingdian.security;

import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.domain.UserStatus;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.verification.service.VerificationBadgeService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 当前登录用户与权限判定。游客访问受保护接口应返回 403（AC-01）。
 */
@Service
public class CurrentUserService {

    private final UserAccountRepository userAccountRepository;
    private final VerificationBadgeService verificationBadgeService;

    public CurrentUserService(UserAccountRepository userAccountRepository,
                              VerificationBadgeService verificationBadgeService) {
        this.userAccountRepository = userAccountRepository;
        this.verificationBadgeService = verificationBadgeService;
    }

    public UUID getUserId() {
        return findUserId()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "请先登录"));
    }

    /**
     * 游客访问公开内容时返回空，不抛出登录异常。
     */
    public Optional<UUID> findUserId() {
        return currentPrincipal()
                .map(JwtPrincipal::subjectId)
                .map(subject -> {
                    try {
                        return UUID.fromString(subject);
                    } catch (IllegalArgumentException ex) {
                        throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已失效，请重新登录");
                    }
                });
    }

    public UserAccount getUser() {
        UUID userId = getUserId();
        return userAccountRepository.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号不存在或已停用"));
    }

    public boolean isRealnameVerified() {
        return getUser().getRealnameStatus() == RealnameStatus.VERIFIED;
    }

    /**
     * 是否对该 USCC 公司具备发评资格的核验（存在 APPROVED 记录且在窗口内）。
     */
    public boolean isVerifiedForCompany(String uscc) {
        return verificationBadgeService.isVerifiedForCompany(getUserId(), uscc);
    }

    private Optional<JwtPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            return Optional.empty();
        }
        if ("user".equals(principal.audience()) || "admin".equals(principal.audience())) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
