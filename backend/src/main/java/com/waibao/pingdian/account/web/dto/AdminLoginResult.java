package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.AdminRole;

import java.time.Instant;

public record AdminLoginResult(String token, AdminRole role, Instant expiresAt) {
}
