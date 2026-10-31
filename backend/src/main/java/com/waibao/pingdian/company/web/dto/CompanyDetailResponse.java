package com.waibao.pingdian.company.web.dto;

import com.waibao.pingdian.company.domain.CompanyStatus;
import com.waibao.pingdian.company.domain.Source;

import java.math.BigDecimal;
import java.util.List;

public record CompanyDetailResponse(
        String uscc,
        String name,
        String shortName,
        String city,
        String industry,
        String companyType,
        Source source,
        String sourceLabel,
        CompanyStatus status,
        int reviewCount,
        BigDecimal avgScore,
        boolean scoreVisible,
        boolean hasReviews,
        String scoreNote,
        List<String> aliases,
        List<DimensionScore> dimensions,
        int responseCount,
        String redirectTo
) {
    public record DimensionScore(String code, BigDecimal average, int sampleCount, boolean enoughSample) {
    }
}
