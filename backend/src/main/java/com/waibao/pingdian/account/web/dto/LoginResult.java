package com.waibao.pingdian.account.web.dto;

import java.time.Instant;

public record LoginResult(String token, Instant expiresAt, UserProfile user) {
}
