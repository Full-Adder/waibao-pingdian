package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理端用户列表条目。手机号仅掩码，不返回明文。
 */
public record AdminUserListItem(
        UUID id,
        String phone,
        String phoneMasked,
        RealnameStatus realnameStatus,
        int contributionScore,
        UserStatus status,
        Instant createdAt,
        String alias,
        long approvedVerificationCount,
        long publishedReviewCount
) {
}
