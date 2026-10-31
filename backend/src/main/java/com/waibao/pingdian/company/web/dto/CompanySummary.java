package com.waibao.pingdian.company.web.dto;

import com.waibao.pingdian.company.domain.Source;

import java.math.BigDecimal;

public record CompanySummary(
        String uscc,
        String name,
        String shortName,
        String city,
        String industry,
        String companyType,
        Source source,
        int reviewCount,
        BigDecimal avgScore,
        boolean scoreVisible,
        boolean hasReviews
) {
}
