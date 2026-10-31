package com.waibao.pingdian.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 种子管理员：仅在 ADMIN_SEED_ACCOUNT / ADMIN_SEED_PASSWORD 存在时 upsert。
 * 凭据只来自环境变量，不写死在代码或仓库中。
 */
@Component
public class AdminSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String seedAccount;
    private final String seedPassword;
    private final String seedRole;

    public AdminSeedRunner(JdbcTemplate jdbcTemplate,
                           PasswordEncoder passwordEncoder,
                           @org.springframework.beans.factory.annotation.Value("${admin.seed.account:}") String seedAccount,
                           @org.springframework.beans.factory.annotation.Value("${admin.seed.password:}") String seedPassword,
                           @org.springframework.beans.factory.annotation.Value("${admin.seed.role:SUPER_ADMIN}") String seedRole) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.seedAccount = seedAccount;
        this.seedPassword = seedPassword;
        this.seedRole = seedRole;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedAccount == null || seedAccount.isBlank() || seedPassword == null || seedPassword.isBlank()) {
            log.info("未配置 ADMIN_SEED_ACCOUNT/ADMIN_SEED_PASSWORD，跳过管理员种子");
            return;
        }
        String role = seedRole == null ? "" : seedRole.trim().toUpperCase();
        if (!role.equals("SUPER_ADMIN") && !role.equals("CONTENT_ADMIN")) {
            log.warn("ADMIN_SEED_ROLE 非法（仅支持 SUPER_ADMIN / CONTENT_ADMIN），跳过种子");
            return;
        }
        try {
            String passwordHash = passwordEncoder.encode(seedPassword);
            jdbcTemplate.update("""
                    INSERT INTO admin_user (account, password_hash, role, status)
                    VALUES (?, ?, ?, 'ACTIVE')
                    ON CONFLICT (account)
                    DO UPDATE SET password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, status = 'ACTIVE'
                    """, seedAccount.trim(), passwordHash, role);
            log.info("管理员种子已写入：account={} role={}", seedAccount.trim(), role);
        } catch (Exception ex) {
            log.warn("管理员种子写入失败：{}", ex.getClass().getSimpleName());
        }
    }
}
