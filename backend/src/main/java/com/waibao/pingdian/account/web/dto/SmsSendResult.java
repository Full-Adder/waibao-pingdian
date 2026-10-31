package com.waibao.pingdian.account.web.dto;

public record SmsSendResult(int resendAfterSeconds, int expiresInSeconds) {
}
