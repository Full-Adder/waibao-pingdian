package com.waibao.pingdian.account.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RealnameRequest(
        @NotBlank(message = "姓名不能为空")
        @Size(min = 2, max = 32, message = "姓名长度不正确")
        String name,

        @NotBlank(message = "证件号不能为空")
        @Size(min = 18, max = 18, message = "证件号必须为 18 位")
        String idNumber
) {
}
