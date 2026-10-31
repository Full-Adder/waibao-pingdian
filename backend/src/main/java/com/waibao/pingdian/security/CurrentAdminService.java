package com.waibao.pingdian.security;

import com.waibao.pingdian.account.domain.AdminStatus;
import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.account.repo.AdminUserRepository;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 当前管理员：从 admin audience 的 JWT principal 解析，并确认账号仍为 ACTIVE。
 */
@Service
public class CurrentAdminService {

    private final AdminUserRepository adminUserRepository;

    public CurrentAdminService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    public AdminUser getAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录管理后台");
        }
        if (!"admin".equals(principal.audience())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可操作");
        }
        try {
            return adminUserRepository.findById(java.util.UUID.fromString(principal.subjectId()))
                    .filter(admin -> admin.getStatus() == AdminStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "管理员账号不存在或已停用"));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }
    }
}
