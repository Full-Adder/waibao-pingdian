package com.waibao.pingdian.company.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImportCreateRequest(
        @NotBlank(message = "城市不能为空")
        @Size(max = 64, message = "城市最多 64 字")
        String city,

        @NotBlank(message = "行业不能为空")
        @Size(max = 64, message = "行业最多 64 字")
        String industry,

        String triggerType
) {
}
