package com.waibao.pingdian.account.web.dto;

import com.waibao.pingdian.account.domain.IdentityStatus;

public record IdentityInfo(String alias, IdentityStatus status) {
}
