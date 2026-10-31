package com.waibao.pingdian.audit.repo;

import com.waibao.pingdian.audit.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.UUID;

public interface AdminAuditLogRepository
        extends JpaRepository<AdminAuditLog, UUID>, JpaSpecificationExecutor<AdminAuditLog> {

    long countByActorIdAndActionAndCreatedAtGreaterThanEqual(UUID actorId, String action, Instant from);

    boolean existsByActionAndTargetTypeAndTargetId(String action, String targetType, String targetId);
}
