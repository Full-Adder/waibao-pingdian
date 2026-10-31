package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.RealnameStatus;

import java.time.Instant;

public record RealnameResult(
        boolean success,
        RealnameStatus realnameStatus,
        int failCount,
        Instant lockedUntil,
        String reasonCode,
        String idMasked
) {
}
