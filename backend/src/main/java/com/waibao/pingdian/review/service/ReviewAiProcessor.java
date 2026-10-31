package com.waibao.pingdian.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 初审处理器：校验状态后调用风险服务与 ReviewAiRouter，落库状态/命中项/置信度/审计。
 * 同步与 MQ 消费共用，重复消息看到状态已不是 MACHINE_REVIEWING 时直接跳过。
 */
@Service
public class ReviewAiProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReviewAiProcessor.class);

    private final ReviewRepository reviewRepository;
    private final UserAccountRepository userAccountRepository;
    private final ReviewRiskService riskService;
    private final ReviewAiRouter aiRouter;
    private final ReviewStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public ReviewAiProcessor(ReviewRepository reviewRepository,
                             UserAccountRepository userAccountRepository,
                             ReviewRiskService riskService,
                             ReviewAiRouter aiRouter,
                             ReviewStateMachine stateMachine,
                             ObjectMapper objectMapper) {
        this.reviewRepository = reviewRepository;
        this.userAccountRepository = userAccountRepository;
        this.riskService = riskService;
        this.aiRouter = aiRouter;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(UUID reviewId, String ip, String device, boolean isEdit) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null) {
            log.warn("review not found for ai review: {}", reviewId);
            return;
        }
        if (review.getStatus() != ReviewStatus.MACHINE_REVIEWING) {
            log.info("review not in machine reviewing, skip: reviewId={} status={}",
                    reviewId, review.getStatus());
            return;
        }
        UserAccount user = userAccountRepository.findById(review.getUserId()).orElse(null);
        if (user == null) {
            log.warn("review owner not found, skip ai review: reviewId={}", reviewId);
            return;
        }
        List<String> riskSignals = riskService.assess(user.getId(), ip, device, user.getCreatedAt());
        boolean copyDetected = riskService.detectCopy(user.getId(), review.getContent());
        aiRouter.evaluate(review, copyDetected, riskSignals, isEdit);
    }

    /**
     * 消费重试耗尽后的兜底：仍处于机器审核的点评转人工，避免卡在 MACHINE_REVIEWING。
     */
    @Transactional
    public void markManualAfterFailure(UUID reviewId, String error) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null || review.getStatus() != ReviewStatus.MACHINE_REVIEWING) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reasonCode", "PROCESSING_ERROR");
        detail.put("message", "AI 初审处理失败，已转人工");
        detail.put("error", error);
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (Exception ex) {
            json = "{\"reasonCode\":\"PROCESSING_ERROR\"}";
        }
        stateMachine.transition(review, ReviewStatus.PENDING_MANUAL, ReviewActorType.SYSTEM, null,
                "AI_REVIEW_FAILED", "AI_REVIEW", "PROCESSING_ERROR", json);
        log.warn("review moved to PENDING_MANUAL after ai review failure: reviewId={}", reviewId);
    }
}
