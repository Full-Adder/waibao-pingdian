package com.waibao.pingdian.review.repo;

import com.waibao.pingdian.review.domain.ReviewEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewEvidenceRepository extends JpaRepository<ReviewEvidence, UUID> {

    List<ReviewEvidence> findByReviewId(UUID reviewId);

    void deleteByReviewId(UUID reviewId);

    boolean existsByReviewIdAndMaterialStatus(UUID reviewId, String materialStatus);
}
