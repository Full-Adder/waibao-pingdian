package com.waibao.pingdian.account.repo;

import com.waibao.pingdian.account.domain.ContributionLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface ContributionLedgerRepository extends JpaRepository<ContributionLedger, UUID> {

    boolean existsByUserIdAndReasonAndRefId(UUID userId, String reason, String refId);

    long countByUserIdAndReasonAndCreatedAtGreaterThanEqual(UUID userId, String reason, Instant from);
}
