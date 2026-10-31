package com.waibao.pingdian.review.web.dto;

/**
 * 审核理由模板：动作 + 原因码 + 文案。
 */
public record ReasonTemplateView(String reasonCode, String text) {
}
