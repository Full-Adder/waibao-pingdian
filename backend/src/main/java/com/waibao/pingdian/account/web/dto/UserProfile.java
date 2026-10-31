package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserProfile(
        UUID id,
        String phone,
        RealnameStatus realnameStatus,
        String userState,
        int contributionScore,
        UserStatus status,
        IdentityInfo identity,
        Instant createdAt
) {
}
