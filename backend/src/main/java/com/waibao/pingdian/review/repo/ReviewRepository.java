package com.waibao.pingdian.review.repo;

import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    List<Review> findByUsccAndStatusInOrderByPublishedAtDesc(String uscc, Collection<ReviewStatus> statuses);

    List<Review> findByUserIdAndUsccAndStatusIn(UUID userId, String uscc, Collection<ReviewStatus> statuses);

    List<Review> findByUserIdAndUscc(UUID userId, String uscc);

    long countByUserIdAndUsccAndStatusIn(UUID userId, String uscc, Collection<ReviewStatus> statuses);

    List<Review> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<Review> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Review> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, ReviewStatus status, Pageable pageable);

    Page<Review> findByStatusOrderByPublishedAtDesc(ReviewStatus status, Pageable pageable);

    List<Review> findByStatusOrderByCreatedAtAsc(ReviewStatus status);

    Optional<Review> findFirstByUserIdAndUsccAndStatusAndWithdrawnAtAfterOrderByWithdrawnAtDesc(
            UUID userId, String uscc, ReviewStatus status, Instant after);

    long countByUserIdAndStatus(UUID userId, ReviewStatus status);
}
