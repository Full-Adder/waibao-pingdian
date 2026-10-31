package com.waibao.pingdian.review.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;
import java.util.UUID;

/**
 * 批量处置请求；逐条执行，部分失败不回滚已成功项。
 */
public record ReviewBatchDispositionRequest(
        @JsonAlias("reviewIds") List<UUID> ids,
        String action,
        String reasonCode,
        @JsonAlias("reasonText") String note
) {
}
