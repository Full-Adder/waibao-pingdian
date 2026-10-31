package com.waibao.pingdian.review.web.dto;

import java.util.List;

/**
 * 批量处置结果：逐条状态，部分失败不影响其他条目。
 */
public record ReviewBatchDispositionResult(
        int total,
        int succeeded,
        int failed,
        List<ReviewDispositionResult> results
) {
}
