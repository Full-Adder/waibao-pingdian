package com.waibao.pingdian.review.service;

import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.repo.CompanyRepository;
import com.waibao.pingdian.review.config.RankingProperties;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewEvidenceRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.review.web.dto.ReviewItemView;
import com.waibao.pingdian.verification.domain.VerificationBadge;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * 公司公开点评列表（R12 加权排序）：核验强度 + 佐证 + 时间衰减，复制/争议降权。
 */
@Service
public class CompanyReviewService {

    private static final EnumSet<ReviewStatus> PUBLIC_STATUSES =
            EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.DISPUTED, ReviewStatus.FOLDED);

    private final CompanyRepository companyRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewService reviewService;
    private final RankingProperties rankingProperties;

    public CompanyReviewService(CompanyRepository companyRepository,
                                ReviewRepository reviewRepository,
                                ReviewEvidenceRepository evidenceRepository,
                                ReviewService reviewService,
                                RankingProperties rankingProperties) {
        this.companyRepository = companyRepository;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.reviewService = reviewService;
        this.rankingProperties = rankingProperties;
    }

    public PageResult<ReviewItemView> list(String rawUscc, int page, int size) {
        String uscc = rawUscc == null ? "" : rawUscc.trim().toUpperCase();
        if (!companyRepository.existsById(uscc)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "公司档案不存在");
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        List<Weighted> weighted = new ArrayList<>();
        for (Review review : reviewRepository.findByUsccAndStatusInOrderByPublishedAtDesc(uscc,
                EnumSet.copyOf(PUBLIC_STATUSES))) {
            weighted.add(new Weighted(review, weightOf(review)));
        }
        weighted.sort(Comparator.comparingDouble(Weighted::weight).reversed()
                .thenComparing(w -> w.review().getPublishedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(w -> w.review().getId()));

        int from = Math.min((safePage - 1) * safeSize, weighted.size());
        int to = Math.min(from + safeSize, weighted.size());
        List<ReviewItemView> items = weighted.subList(from, to).stream()
                .map(w -> reviewService.toView(w.review()))
                .toList();
        return PageResult.of(items, safePage, safeSize, weighted.size());
    }

    double weightOf(Review review) {
        double base;
        VerificationBadge badge = review.getVerificationBadge();
        base = badge == VerificationBadge.FORMER
                ? rankingProperties.getFormerWeight()
                : rankingProperties.getVerifiedWeight();
        if (evidenceRepository.existsByReviewIdAndMaterialStatus(review.getId(), "ACTIVE")) {
            base += rankingProperties.getEvidenceBonus();
        }
        if (rankingProperties.getTimeDecay().isEnabled()) {
            Instant threshold = Instant.now().atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .minusMonths(rankingProperties.getTimeDecay().getMonths())
                    .toInstant();
            if (review.getPublishedAt() == null || review.getPublishedAt().isBefore(threshold)) {
                base += rankingProperties.getTimeDecay().getWeight();
            } else {
                base += 1.0;
            }
        } else {
            base += 1.0;
        }
        if (copyDetected(review)) {
            base *= rankingProperties.getCopyPenalty();
        }
        if (review.getStatus() == ReviewStatus.DISPUTED) {
            base *= rankingProperties.getDisputedPenalty();
        }
        return base;
    }

    private boolean copyDetected(Review review) {
        return review.getAiFlags() != null && review.getAiFlags().path("copyDetected").asBoolean(false);
    }

    private record Weighted(Review review, double weight) {
    }
}
