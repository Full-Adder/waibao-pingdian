package com.waibao.pingdian.complaint.repo;

import com.waibao.pingdian.complaint.domain.EnterpriseResponse;
import com.waibao.pingdian.complaint.domain.EnterpriseResponseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnterpriseResponseRepository extends JpaRepository<EnterpriseResponse, UUID> {

    List<EnterpriseResponse> findByReviewIdAndStatusOrderByCreatedAtDesc(
            UUID reviewId, EnterpriseResponseStatus status);

    List<EnterpriseResponse> findByUsccAndStatusOrderByCreatedAtDesc(
            String uscc, EnterpriseResponseStatus status);

    List<EnterpriseResponse> findByReviewIdOrderByCreatedAtDesc(UUID reviewId);

    List<EnterpriseResponse> findByReviewIdAndStatusInOrderByCreatedAtDesc(
            UUID reviewId, List<EnterpriseResponseStatus> statuses);

    Optional<EnterpriseResponse> findFirstByReviewIdAndStatusOrderByCreatedAtDesc(
            UUID reviewId, EnterpriseResponseStatus status);

    List<EnterpriseResponse> findByComplaintIdAndStatusOrderByCreatedAtDesc(
            UUID complaintId, EnterpriseResponseStatus status);

    Page<EnterpriseResponse> findByStatus(EnterpriseResponseStatus status, Pageable pageable);
}
