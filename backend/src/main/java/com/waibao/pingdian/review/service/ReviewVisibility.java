package com.waibao.pingdian.review.service;

import com.waibao.pingdian.review.domain.ReviewStatus;

import java.util.Set;

/**
 * PRD 第 6 章可见性与索引表。
 */
public final class ReviewVisibility {

    private static final Set<ReviewStatus> PUBLIC_STATUSES =
            Set.of(ReviewStatus.PUBLISHED, ReviewStatus.DISPUTED, ReviewStatus.FOLDED);

    private static final Set<ReviewStatus> INDEXABLE_STATUSES =
            Set.of(ReviewStatus.PUBLISHED, ReviewStatus.DISPUTED);

    private ReviewVisibility() {
    }

    public static boolean isPubliclyVisible(ReviewStatus status) {
        return status != null && PUBLIC_STATUSES.contains(status);
    }

    public static boolean isIndexable(ReviewStatus status) {
        return status != null && INDEXABLE_STATUSES.contains(status);
    }

    public static boolean isAuthorOnly(ReviewStatus status) {
        return status != null && !isPubliclyVisible(status);
    }
}
