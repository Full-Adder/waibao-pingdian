package com.waibao.pingdian.account.web.dto;

public record IdentityChangeResult(IdentityDetail identity, long remainingChangesThisYear) {
}
