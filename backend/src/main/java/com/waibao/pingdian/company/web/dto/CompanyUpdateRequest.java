package com.waibao.pingdian.company.web.dto;

import com.waibao.pingdian.company.domain.CompanyStatus;

import jakarta.validation.constraints.Size;

public record CompanyUpdateRequest(
        @Size(max = 255, message = "公司名称最多 255 字")
        String name,
        @Size(max = 255, message = "公司简称最多 255 字")
        String shortName,
        @Size(max = 64, message = "城市最多 64 字")
        String city,
        @Size(max = 64, message = "行业最多 64 字")
        String industry,
        @Size(max = 64, message = "公司类型最多 64 字")
        String companyType,
        CompanyStatus status,
        @Size(max = 40, message = "原因码最多 40 字")
        String reason,
        @Size(max = 500, message = "备注最多 500 字")
        String note,
        @Size(max = 64, message = "工单号最多 64 字")
        String ticketId
) {
}
