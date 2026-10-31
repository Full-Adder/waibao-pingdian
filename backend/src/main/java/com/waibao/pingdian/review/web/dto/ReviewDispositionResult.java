package com.waibao.pingdian.review.web.dto;

import java.util.UUID;

/**
 * 批量处置的单条结果。
 */
public record ReviewDispositionResult(
        UUID reviewId,
        boolean success,
        String status,
        Integer code,
        String message
) {
}
