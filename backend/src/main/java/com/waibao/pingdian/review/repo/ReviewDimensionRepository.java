package com.waibao.pingdian.review.repo;

import com.waibao.pingdian.review.domain.ReviewDimension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewDimensionRepository extends JpaRepository<ReviewDimension, UUID> {

    List<ReviewDimension> findByReviewIdOrderByDimensionCode(UUID reviewId);

    void deleteByReviewId(UUID reviewId);
}
