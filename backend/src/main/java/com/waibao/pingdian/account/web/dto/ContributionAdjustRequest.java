package com.waibao.pingdian.account.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理端贡献值调整。refId 可选，用于重复提交时命中唯一约束实现幂等；缺省由服务端生成。
 */
public record ContributionAdjustRequest(
        @NotNull @Min(-1000) @Max(1000) Integer delta,
        @NotBlank @Size(max = 500) String reason,
        String refId
) {
}
