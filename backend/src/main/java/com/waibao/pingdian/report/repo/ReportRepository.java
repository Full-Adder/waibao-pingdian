package com.waibao.pingdian.report.repo;

import com.waibao.pingdian.report.domain.Report;
import com.waibao.pingdian.report.domain.ReportStatus;
import com.waibao.pingdian.report.domain.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    boolean existsByUserIdAndTargetTypeAndTargetIdAndCreatedAtAfter(
            UUID userId, ReportTargetType targetType, UUID targetId, Instant after);

    List<Report> findByUserIdAndTargetTypeAndTargetIdAndCreatedAtAfterOrderByCreatedAtAsc(
            UUID userId, ReportTargetType targetType, UUID targetId, Instant after);

    Page<Report> findByStatusOrderByCreatedAtAsc(ReportStatus status, Pageable pageable);

    Page<Report> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Report> findByStatusOrderByCreatedAtDesc(ReportStatus status, Pageable pageable);

    @Query("select count(distinct r.userId) from Report r "
            + "where r.targetType = :targetType and r.targetId = :targetId")
    long countDistinctUsersByTarget(@Param("targetType") ReportTargetType targetType,
                                    @Param("targetId") UUID targetId);
}
