package com.waibao.pingdian.company.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MergePathRequest(
        @NotBlank(message = "合并原因不能为空")
        @Size(max = 500, message = "合并原因最多 500 字")
        String reason,

        @Size(max = 64, message = "工单号最多 64 字")
        String ticketId
) {
}
