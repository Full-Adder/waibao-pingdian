package com.waibao.pingdian.company.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MergeCompanyRequest(
        @NotBlank(message = "源 USCC 不能为空")
        @Size(min = 18, max = 18, message = "源 USCC 必须为 18 位")
        String sourceUscc,

        @NotBlank(message = "目标 USCC 不能为空")
        @Size(min = 18, max = 18, message = "目标 USCC 必须为 18 位")
        String targetUscc,

        @NotBlank(message = "合并原因不能为空")
        @Size(max = 500, message = "合并原因最多 500 字")
        String reason,

        @Size(max = 64, message = "工单号最多 64 字")
        String ticketId
) {
}
