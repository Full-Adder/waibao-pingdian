package com.waibao.pingdian.review.web.dto;

import java.util.UUID;

/**
 * 审核详情中的佐证材料入口；url 为签名 URL 占位地址（真实签名需带工单号走材料接口）。
 */
public record ReviewEvidenceMaterialView(
        UUID evidenceId,
        String type,
        String materialStatus,
        String url
) {
}
