package com.waibao.pingdian.review.service;

import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.company.service.CompanyAggregateService;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewAudit;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewAuditRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 集中式状态机：只允许 PRD 第 6 章列出的迁移；每次迁移写 review_audit，
 * 并在同一事务内重算受影响公司的聚合字段。
 */
@Service
public class ReviewStateMachine {

    private static final Map<ReviewStatus, Set<ReviewStatus>> ALLOWED = allowedTransitions();

    private static final Set<ReviewStatus> AGGREGATE_STATUSES = EnumSet.of(
            ReviewStatus.PUBLISHED, ReviewStatus.DISPUTED, ReviewStatus.FOLDED,
            ReviewStatus.EDITING, ReviewStatus.WITHDRAWN, ReviewStatus.REJECTED, ReviewStatus.REMOVED);

    private final ReviewRepository reviewRepository;
    private final ReviewAuditRepository auditRepository;
    private final CompanyAggregateService aggregateService;

    public ReviewStateMachine(ReviewRepository reviewRepository,
                              ReviewAuditRepository auditRepository,
                              CompanyAggregateService aggregateService) {
        this.reviewRepository = reviewRepository;
        this.auditRepository = auditRepository;
        this.aggregateService = aggregateService;
    }

    @Transactional
    public void transition(Review review, ReviewStatus target, ReviewActorType actorType, UUID actorId,
                           String action, String stage, String reasonCode, String detail) {
        ReviewStatus current = review.getStatus();
        if (!canTransition(current, target)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "不允许的状态迁移：" + current + " → " + target);
        }
        review.setStatus(target);
        if (target == ReviewStatus.WITHDRAWN) {
            review.setWithdrawnAt(java.time.Instant.now());
        }
        reviewRepository.saveAndFlush(review);

        ReviewAudit audit = new ReviewAudit();
        audit.setReviewId(review.getId());
        audit.setStage(stage == null ? action : stage);
        audit.setActorId(actorId);
        audit.setActorType(actorType);
        audit.setAction(action);
        audit.setFromStatus(current);
        audit.setToStatus(target);
        audit.setReasonCode(reasonCode);
        audit.setDetail(detail);
        auditRepository.save(audit);

        if (AGGREGATE_STATUSES.contains(target)) {
            aggregateService.recompute(review.getUscc());
        }
    }

    public static boolean canTransition(ReviewStatus from, ReviewStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    private static Map<ReviewStatus, Set<ReviewStatus>> allowedTransitions() {
        Map<ReviewStatus, Set<ReviewStatus>> map = new EnumMap<>(ReviewStatus.class);
        map.put(ReviewStatus.DRAFT, EnumSet.of(ReviewStatus.MACHINE_REVIEWING));
        map.put(ReviewStatus.MACHINE_REVIEWING,
                EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.PENDING_MANUAL, ReviewStatus.REJECTED));
        map.put(ReviewStatus.PENDING_MANUAL,
                EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.FOLDED, ReviewStatus.REJECTED));
        map.put(ReviewStatus.PUBLISHED, EnumSet.of(ReviewStatus.EDITING, ReviewStatus.WITHDRAWN,
                ReviewStatus.DISPUTED, ReviewStatus.FOLDED, ReviewStatus.REMOVED));
        map.put(ReviewStatus.EDITING, EnumSet.of(ReviewStatus.MACHINE_REVIEWING));
        map.put(ReviewStatus.WITHDRAWN, EnumSet.noneOf(ReviewStatus.class));
        map.put(ReviewStatus.DISPUTED,
                EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.FOLDED, ReviewStatus.REMOVED));
        map.put(ReviewStatus.FOLDED, EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.REMOVED));
        map.put(ReviewStatus.REJECTED, EnumSet.of(ReviewStatus.PENDING_MANUAL));
        map.put(ReviewStatus.REMOVED, EnumSet.noneOf(ReviewStatus.class));
        return map;
    }
}
