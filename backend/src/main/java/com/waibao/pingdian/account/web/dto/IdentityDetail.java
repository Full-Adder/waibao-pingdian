package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.IdentityStatus;

import java.time.Instant;

public record IdentityDetail(
        String alias,
        IdentityStatus status,
        int changeCount,
        int changeCost,
        int cooldownDays,
        Instant nextChangeAllowedAt,
        int insufficientPoints,
        long remainingCooldownDays,
        long remainingChangesThisYear,
        boolean canChange
) {
}
