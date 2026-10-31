package com.waibao.pingdian.review.repo;

import com.waibao.pingdian.review.domain.ReviewAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewAuditRepository extends JpaRepository<ReviewAudit, UUID> {

    List<ReviewAudit> findByReviewIdOrderByCreatedAtAsc(UUID reviewId);

    List<ReviewAudit> findByReviewIdOrderByCreatedAtDesc(UUID reviewId);

    Optional<ReviewAudit> findFirstByReviewIdAndActionOrderByCreatedAtDesc(UUID reviewId, String action);

    boolean existsByReviewIdAndAction(UUID reviewId, String action);
}
