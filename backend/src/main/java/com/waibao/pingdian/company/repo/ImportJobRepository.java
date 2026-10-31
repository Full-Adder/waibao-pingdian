package com.waibao.pingdian.company.repo;

import com.waibao.pingdian.company.domain.ImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    Page<ImportJob> findAllByOrderByStartedAtDesc(Pageable pageable);
}
