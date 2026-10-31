package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.domain.AdminStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminProfile(UUID id, String account, AdminRole role, AdminStatus status, Instant lastLoginAt) {
}
