package com.waibao.pingdian.security;

import com.waibao.pingdian.account.domain.AdminStatus;
import com.waibao.pingdian.account.domain.UserStatus;
import com.waibao.pingdian.account.repo.AdminUserRepository;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 鉴权：
 * - /api/admin/** 要求 admin audience，只校验管理员存在且 ACTIVE（管理端不做单设备校验）；
 * - 其余要求 user audience，除了签名与 audience 外，还必须校验用户存在、status=ACTIVE、
 *   Redis 会话 jti（多实例共享）与 token 的 jti 一致，实现单设备会话；
 *   Redis key 缺失（旧数据/Redis 重启）时回退数据库 session_jti，两者都不匹配返回 401。
 * 校验失败不在此直接拒绝，交由 Security 的授权规则返回 401/403 JSON。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_AUDIENCE = "admin";
    private static final String USER_AUDIENCE = "user";

    private final JwtService jwtService;
    private final SessionRegistry sessionRegistry;
    private final UserAccountRepository userAccountRepository;
    private final AdminUserRepository adminUserRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   SessionRegistry sessionRegistry,
                                   UserAccountRepository userAccountRepository,
                                   AdminUserRepository adminUserRepository) {
        this.jwtService = jwtService;
        this.sessionRegistry = sessionRegistry;
        this.userAccountRepository = userAccountRepository;
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length());
            boolean adminPath = request.getRequestURI().startsWith("/api/admin/");
            Optional<JwtPrincipal> principal = jwtService.parse(
                    token, adminPath ? ADMIN_AUDIENCE : USER_AUDIENCE);
            if (principal.isEmpty() && adminPath) {
                // 已登录普通用户访问管理端时认证为 USER，由授权规则返回 403 而不是 401。
                jwtService.parse(token, USER_AUDIENCE).ifPresent(this::authenticateUser);
            }
            principal.ifPresent(value -> {
                if (adminPath) {
                    authenticateAdmin(value);
                } else {
                    authenticateUser(value);
                }
            });
        }
        chain.doFilter(request, response);
    }

    private void authenticateAdmin(JwtPrincipal value) {
        UUID adminId;
        try {
            adminId = UUID.fromString(value.subjectId());
        } catch (IllegalArgumentException ex) {
            return;
        }
        boolean active = adminUserRepository.findById(adminId)
                .map(admin -> admin.getStatus() == AdminStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            return;
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ADMIN"));
        if (value.role() != null && !value.role().isBlank()) {
            authorities.add(new SimpleGrantedAuthority(value.role()));
        }
        setAuthentication(value, authorities);
    }

    private void authenticateUser(JwtPrincipal value) {
        UUID userId;
        try {
            userId = UUID.fromString(value.subjectId());
        } catch (IllegalArgumentException ex) {
            return;
        }
        var user = userAccountRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        String expectedJti = sessionRegistry.currentJti(userId).orElse(user.getSessionJti());
        boolean valid = value.jti() != null && value.jti().equals(expectedJti);
        if (!valid) {
            return;
        }
        setAuthentication(value, List.of(new SimpleGrantedAuthority("USER")));
    }

    private void setAuthentication(JwtPrincipal principal, List<SimpleGrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
