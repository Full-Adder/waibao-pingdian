package com.waibao.pingdian.verification.repo;

import com.waibao.pingdian.verification.domain.UserVerification;
import com.waibao.pingdian.verification.domain.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserVerificationRepository extends JpaRepository<UserVerification, UUID> {

    Page<UserVerification> findByUserId(UUID userId, Pageable pageable);

    Page<UserVerification> findByStatus(VerificationStatus status, Pageable pageable);

    Page<UserVerification> findAll(Pageable pageable);

    List<UserVerification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<UserVerification> findFirstByUserIdAndCompanyUsccAndStatusOrderByPeriodEndDescReviewedAtDesc(
            UUID userId, String companyUscc, VerificationStatus status);

    boolean existsByUserIdAndCompanyUsccAndStatus(UUID userId, String companyUscc, VerificationStatus status);

    long countByUserIdAndStatus(UUID userId, VerificationStatus status);
}
