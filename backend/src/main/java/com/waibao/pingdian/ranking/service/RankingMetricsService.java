package com.waibao.pingdian.ranking.service;

import com.waibao.pingdian.review.config.RankingProperties;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewEvidenceRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.ranking.domain.CompanyMetrics;
import com.waibao.pingdian.verification.domain.VerificationBadge;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

/**
 * 单公司榜单指标（R13），供榜单计算与后续看板复用。
 *
 * 推荐榜样本 = PUBLISHED；加权分 = 各 PUBLISHED 评价权重之和 / 样本数。
 * 待改善公开样本 = PUBLISHED + DISPUTED；低分集中度与争议率分母均为公开样本数。
 */
@Service
public class RankingMetricsService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final EnumSet<ReviewStatus> PUBLIC_SAMPLE_STATUSES =
            EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.DISPUTED);
    private static final double IMPROVE_LOW_SCORE_WEIGHT = 0.6;
    private static final double IMPROVE_DISPUTE_WEIGHT = 0.4;

    private final ReviewRepository reviewRepository;
    private final ReviewEvidenceRepository evidenceRepository;
    private final RankingProperties rankingProperties;

    public RankingMetricsService(ReviewRepository reviewRepository,
                                 ReviewEvidenceRepository evidenceRepository,
                                 RankingProperties rankingProperties) {
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.rankingProperties = rankingProperties;
    }

    public CompanyMetrics compute(String uscc, String name) {
        List<Review> reviews = reviewRepository.findByUsccAndStatusInOrderByPublishedAtDesc(
                uscc, EnumSet.copyOf(PUBLIC_SAMPLE_STATUSES));
        int recommendSample = 0;
        double weightSum = 0.0;
        int lowScoreCount = 0;
        int disputedCount = 0;
        for (Review review : reviews) {
            if (review.isEverDisputed()) {
                disputedCount++;
            }
            if (review.getTotalScore() == 1 || review.getTotalScore() == 2) {
                lowScoreCount++;
            }
            if (review.getStatus() == ReviewStatus.PUBLISHED) {
                recommendSample++;
                weightSum += weightOf(review);
            }
        }
        int improveSample = reviews.size();
        double weightedScore = recommendSample == 0 ? 0.0 : weightSum / recommendSample;
        double lowScoreConcentration = improveSample == 0 ? 0.0
                : (double) lowScoreCount / improveSample;
        double disputeRate = improveSample == 0 ? 0.0 : (double) disputedCount / improveSample;
        double improveScore = IMPROVE_LOW_SCORE_WEIGHT * lowScoreConcentration
                + IMPROVE_DISPUTE_WEIGHT * disputeRate;
        return new CompanyMetrics(
                uscc,
                name,
                recommendSample,
                round(weightedScore),
                improveSample,
                lowScoreCount,
                round(lowScoreConcentration),
                disputedCount,
                round(disputeRate),
                round(improveScore));
    }

    double weightOf(Review review) {
        double weight = review.getVerificationBadge() == VerificationBadge.FORMER
                ? rankingProperties.getFormerWeight()
                : rankingProperties.getVerifiedWeight();
        if (evidenceRepository.existsByReviewIdAndMaterialStatus(review.getId(), "ACTIVE")) {
            weight += rankingProperties.getEvidenceBonus();
        }
        RankingProperties.TimeDecay decay = rankingProperties.getTimeDecay();
        if (decay.isEnabled()) {
            Instant threshold = Instant.now().atZone(BUSINESS_ZONE)
                    .minusMonths(decay.getMonths())
                    .toInstant();
            weight += review.getPublishedAt() != null && !review.getPublishedAt().isBefore(threshold)
                    ? 1.0 : decay.getWeight();
        } else {
            weight += 1.0;
        }
        if (copyDetected(review)) {
            weight *= rankingProperties.getCopyPenalty();
        }
        return weight;
    }

    private static boolean copyDetected(Review review) {
        return review.getAiFlags() != null
                && review.getAiFlags().path("copyDetected").asBoolean(false);
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
