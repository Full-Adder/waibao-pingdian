package com.waibao.pingdian.review.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * 创建/草稿/编辑点评的统一请求。submit 时必填项由服务端逐字段校验。
 */
public record ReviewUpsertRequest(
        UUID id,
        String uscc,
        String periodStart,
        String periodEnd,
        Integer totalScore,
        String content,
        List<DimensionRequest> dimensions,
        String evidenceFileKey,
        List<String> evidenceFileKeys,
        JsonNode tags
) {

    public record DimensionRequest(String code, Integer score) {
    }
}
