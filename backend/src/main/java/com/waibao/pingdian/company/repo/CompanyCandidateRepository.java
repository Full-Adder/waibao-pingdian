package com.waibao.pingdian.company.repo;

import com.waibao.pingdian.company.domain.CandidateStatus;
import com.waibao.pingdian.company.domain.CompanyCandidate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CompanyCandidateRepository extends JpaRepository<CompanyCandidate, UUID> {

    Page<CompanyCandidate> findByStatus(CandidateStatus status, Pageable pageable);

    List<CompanyCandidate> findByCreatedAtAfter(Instant since);
}
