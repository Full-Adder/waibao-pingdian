package com.waibao.pingdian.mq.messages;

import java.util.UUID;

/**
 * AI 初审消息：只携带 review id 与风险判定所需的最小上下文。
 */
public record AiReviewMessage(UUID reviewId, String ip, String device, boolean isEdit) {
}
