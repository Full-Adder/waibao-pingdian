package com.waibao.pingdian.track.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 匿名访问埋点请求：visitorId 由前端生成，不包含手机号、证件号等个人明文。
 */
public record TrackVisitRequest(
        @NotBlank @Size(max = 64) String visitorId,
        @NotBlank @Size(max = 512) String path,
        @Size(max = 512) String userAgent
) {
}
