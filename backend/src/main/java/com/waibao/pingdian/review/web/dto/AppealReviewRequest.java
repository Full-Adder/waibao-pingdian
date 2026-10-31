package com.waibao.pingdian.review.web.dto;

/**
 * 点评申诉请求；statement 选填，不超过 500 字。
 */
public record AppealReviewRequest(String statement) {
}
