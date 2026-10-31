package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.domain.AdminStatus;
import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.account.repo.AdminUserRepository;
import com.waibao.pingdian.account.web.dto.AdminLoginRequest;
import com.waibao.pingdian.account.web.dto.AdminLoginResult;
import com.waibao.pingdian.account.web.dto.AdminProfile;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 管理端登录：账号 + BCrypt 密码；仅 ACTIVE 可登录；签发 aud=admin JWT。
 */
@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AdminAuthService(AdminUserRepository adminUserRepository,
                            PasswordEncoder passwordEncoder,
                            JwtService jwtService) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AdminLoginResult login(AdminLoginRequest request) {
        AdminUser admin = adminUserRepository.findByAccount(request.account().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (admin.getStatus() != AdminStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号已停用");
        }
        admin.setLastLoginAt(Instant.now());
        adminUserRepository.save(admin);

        JwtService.IssuedToken token = jwtService.issueAdminToken(
                admin.getId().toString(), admin.getRole().name(), jwtService.newSessionJti());
        return new AdminLoginResult(token.token(), admin.getRole(), token.expiresAt());
    }

    public AdminProfile profile(String adminId) {
        AdminUser admin = adminUserRepository.findById(java.util.UUID.fromString(adminId))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号不存在"));
        return new AdminProfile(admin.getId(), admin.getAccount(), admin.getRole(),
                admin.getStatus(), admin.getLastLoginAt());
    }
}
