package com.waibao.pingdian.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waibao.pingdian.account.service.ContributionService;
import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.provider.AiModerationProvider;
import com.waibao.pingdian.review.config.ReviewProperties;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewEvidenceRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 初审路由：落库命中项/置信度/规则版本，按结果把 MACHINE_REVIEWING 迁移到
 * PUBLISHED / PENDING_MANUAL / REJECTED；具体指控、复制检测、风险命中一律转人工。
 */
@Service
public class ReviewAiRouter {

    private final AiModerationProvider moderationProvider;
    private final ProvidersProperties providersProperties;
    private final ReviewProperties reviewProperties;
    private final ReviewRepository reviewRepository;
    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewStateMachine stateMachine;
    private final ContributionService contributionService;
    private final ObjectMapper objectMapper;

    public ReviewAiRouter(AiModerationProvider moderationProvider,
                          ProvidersProperties providersProperties,
                          ReviewProperties reviewProperties,
                          ReviewRepository reviewRepository,
                          ReviewEvidenceRepository evidenceRepository,
                          ReviewStateMachine stateMachine,
                          ContributionService contributionService,
                          ObjectMapper objectMapper) {
        this.moderationProvider = moderationProvider;
        this.providersProperties = providersProperties;
        this.reviewProperties = reviewProperties;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.stateMachine = stateMachine;
        this.contributionService = contributionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 在 review 已处于 MACHINE_REVIEWING 后调用；同一事务内完成判定与迁移。
     */
    public void evaluate(Review review, boolean copyDetected, List<String> riskSignals, boolean reReview) {
        boolean hasEvidence = evidenceRepository.existsByReviewIdAndMaterialStatus(review.getId(), "ACTIVE");
        AiModerationProvider.ModerationResult result = moderationProvider.moderate(
                new AiModerationProvider.ModerationInput(review.getContent(), review.getTotalScore(),
                        hasEvidence, copyDetected));

        List<String> flags = result.flags() == null ? List.of() : result.flags();
        boolean specificAllegation = flags.contains("SPECIFIC_ALLEGATION");
        boolean redline = flags.contains("PRIVACY_REDLINE");
        boolean ad = flags.contains("AD_CONTACT");
        boolean noAppeal = redline || ad;
        boolean riskHit = !riskSignals.isEmpty();
        double publishConfidence = providersProperties.getMock().getAi().getPublishConfidence();

        AiModerationProvider.SuggestedStatus suggested = result.suggestedStatus();
        boolean forcedManual = suggested == AiModerationProvider.SuggestedStatus.MANUAL
                || specificAllegation || copyDetected || riskHit
                || result.confidence() < publishConfidence;

        ReviewStatus target;
        if (suggested == AiModerationProvider.SuggestedStatus.REJECTED) {
            target = ReviewStatus.REJECTED;
        } else if (forcedManual) {
            target = ReviewStatus.PENDING_MANUAL;
        } else {
            target = ReviewStatus.PUBLISHED;
        }

        review.setAiConfidence(BigDecimal.valueOf(result.confidence())
                .setScale(3, RoundingMode.HALF_UP));
        review.setAiRulesVersion(result.rulesVersion());
        review.setAiFlags(buildAiFlags(flags, specificAllegation, noAppeal, copyDetected,
                riskSignals, suggested));

        String actionPrefix = reReview ? "AI_RE_REVIEW_" : "AI_";
        if (target == ReviewStatus.PUBLISHED) {
            if (review.getPublishedAt() == null) {
                Instant now = Instant.now();
                review.setPublishedAt(now);
                review.setEditDeadline(now.plus(reviewProperties.getEditWindowDays(), ChronoUnit.DAYS));
            }
            reviewRepository.saveAndFlush(review);
            stateMachine.transition(review, ReviewStatus.PUBLISHED, ReviewActorType.SYSTEM, null,
                    actionPrefix + "PUBLISH", "AI_REVIEW", null, null);
            contributionService.awardReviewPublished(review.getUserId(), review.getId());
        } else if (target == ReviewStatus.REJECTED) {
            String reasonCode = rejectReasonCode(flags);
            String detail = rejectDetail(reasonCode, result.rulesVersion(), noAppeal);
            reviewRepository.saveAndFlush(review);
            stateMachine.transition(review, ReviewStatus.REJECTED, ReviewActorType.SYSTEM, null,
                    actionPrefix + "REJECT", "AI_REVIEW", reasonCode, detail);
        } else {
            reviewRepository.saveAndFlush(review);
            stateMachine.transition(review, ReviewStatus.PENDING_MANUAL, ReviewActorType.SYSTEM, null,
                    actionPrefix + "MANUAL", "AI_REVIEW", null, null);
        }
    }

    private ObjectNode buildAiFlags(List<String> flags, boolean specificAllegation, boolean noAppeal,
                                    boolean copyDetected, List<String> riskSignals,
                                    AiModerationProvider.SuggestedStatus suggested) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode flagArray = node.putArray("flags");
        flags.forEach(flagArray::add);
        node.put("specificAllegation", specificAllegation);
        node.put("noAppeal", noAppeal);
        node.put("copyDetected", copyDetected);
        ArrayNode signals = node.putArray("riskSignals");
        riskSignals.forEach(signals::add);
        node.put("suggestedStatus", suggested == null ? null : suggested.name());
        return node;
    }

    private String rejectReasonCode(List<String> flags) {
        if (flags.contains("PRIVACY_REDLINE")) {
            return "PRIVACY";
        }
        if (flags.contains("AD_CONTACT")) {
            return "AD";
        }
        if (flags.contains("PERSONAL_ATTACK")) {
            return "ABUSE";
        }
        return "OTHER";
    }

    private String rejectDetail(String reasonCode, String rulesVersion, boolean noAppeal) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reasonCode", reasonCode);
        detail.put("message", rejectMessage(reasonCode));
        detail.put("ruleVersion", rulesVersion);
        detail.put("noAppeal", noAppeal);
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception ex) {
            return "{\"reasonCode\":\"" + reasonCode + "\"}";
        }
    }

    static String rejectMessage(String reasonCode) {
        return switch (reasonCode) {
            case "PRIVACY" -> "内容泄露个人隐私（身份证号、手机号等），请删除具体信息后重新提交";
            case "AD" -> "内容包含广告或联系方式，请移除引流信息后重新提交";
            case "ABUSE" -> "内容包含人身攻击，请改为客观描述事实";
            case "FALSE_FACT" -> "内容包含未经证实的违法事实，请补充客观依据";
            case "AI_TRACE" -> "内容模板化痕迹明显，请用真实经历重新撰写";
            default -> "内容不符合社区规范，请修改后重新提交";
        };
    }
}
