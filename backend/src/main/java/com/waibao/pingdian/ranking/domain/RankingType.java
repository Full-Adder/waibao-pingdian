package com.waibao.pingdian.ranking.domain;

public enum RankingType {
    RECOMMEND,
    IMPROVE;

    public static RankingType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("type 不能为空");
        }
        return valueOf(raw.trim().toUpperCase());
    }
}
