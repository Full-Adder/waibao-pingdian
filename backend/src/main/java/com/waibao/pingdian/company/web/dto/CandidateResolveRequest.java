package com.waibao.pingdian.company.web.dto;

import jakarta.validation.constraints.Size;

public record CandidateResolveRequest(
        String action,
        @Size(min = 18, max = 18, message = "USCC 必须为 18 位")
        String uscc,
        @Size(max = 255, message = "公司名称最多 255 字")
        String name,
        @Size(max = 64, message = "城市最多 64 字")
        String city,
        @Size(max = 64, message = "行业最多 64 字")
        String industry,
        @Size(max = 500, message = "备注最多 500 字")
        String note,
        @Size(max = 64, message = "工单号最多 64 字")
        String ticketId
) {
}
