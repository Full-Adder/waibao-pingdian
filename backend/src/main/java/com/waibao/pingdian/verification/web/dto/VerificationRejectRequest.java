package com.waibao.pingdian.verification.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerificationRejectRequest(
        @NotBlank(message = "驳回原因码不能为空")
        String reasonCode,

        @Size(max = 200, message = "备注最多 200 字")
        String note
) {
}
