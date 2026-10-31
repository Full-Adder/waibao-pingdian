package com.waibao.pingdian.review.web.dto;

import java.util.List;

/**
 * 通过 / 折叠 / 驳回各至少 4 条理由模板。
 */
public record ReasonTemplateBundle(
        List<ReasonTemplateView> approve,
        List<ReasonTemplateView> fold,
        List<ReasonTemplateView> reject
) {
}
