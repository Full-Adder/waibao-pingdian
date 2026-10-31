package com.waibao.pingdian.complaint.repo;

import com.waibao.pingdian.complaint.domain.Complaint;
import com.waibao.pingdian.complaint.domain.ComplaintStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    Optional<Complaint> findByReviewId(UUID reviewId);

    boolean existsByReviewId(UUID reviewId);

    Page<Complaint> findByStatus(ComplaintStatus status, Pageable pageable);

    List<Complaint> findByStatusOrderByDeadlineAsc(ComplaintStatus status);

    @Query("select count(c) from Complaint c where c.claimantUscc = ?1 and c.createdAt >= ?2")
    long countRecentByClaimantUscc(String uscc, Instant since);
}
