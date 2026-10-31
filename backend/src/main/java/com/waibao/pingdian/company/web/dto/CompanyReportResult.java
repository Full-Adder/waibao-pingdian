package com.waibao.pingdian.company.web.dto;

import java.util.UUID;

/**
 * 上报结果：命中已有公司返回 company；未命中只返回候选，不创建公司。
 */
public record CompanyReportResult(
        boolean matched,
        boolean pending,
        CompanySummary company,
        UUID candidateId
) {
}
