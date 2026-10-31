package com.waibao.pingdian.audit.web.dto;

import com.waibao.pingdian.account.domain.AdminRole;

import java.time.Instant;
import java.util.UUID;

public record AuditLogItem(
        UUID id,
        UUID actorId,
        AdminRole actorRole,
        String action,
        String targetType,
        String targetId,
        String reasonCode,
        String detail,
        String beforeHash,
        String afterHash,
        String ip,
        String ticketId,
        Instant createdAt
) {
}
