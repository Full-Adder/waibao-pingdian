package com.waibao.pingdian.company.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyReportRequest(
        @NotBlank(message = "公司名称不能为空")
        @Size(max = 255, message = "公司名称最多 255 字")
        String name,

        @Size(max = 64, message = "城市最多 64 字")
        String city,

        @Size(max = 64, message = "行业最多 64 字")
        String industry
) {
}
