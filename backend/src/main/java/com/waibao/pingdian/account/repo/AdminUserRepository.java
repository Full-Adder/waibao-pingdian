package com.waibao.pingdian.account.repo;

import com.waibao.pingdian.account.domain.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    Optional<AdminUser> findByAccount(String account);
}
