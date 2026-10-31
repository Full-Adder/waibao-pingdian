package com.waibao.pingdian.account.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminLoginRequest(
        @NotBlank(message = "账号不能为空")
        @Size(max = 64, message = "账号过长")
        String account,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度不正确")
        String password
) {
}
