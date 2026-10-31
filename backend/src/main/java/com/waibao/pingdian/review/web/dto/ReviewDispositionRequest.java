package com.waibao.pingdian.review.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 单条审核处置请求；reasonCode 与 note 必填。
 */
public record ReviewDispositionRequest(String reasonCode, @JsonAlias("reasonText") String note) {
}
