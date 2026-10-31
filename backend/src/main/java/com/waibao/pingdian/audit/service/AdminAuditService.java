package com.waibao.pingdian.audit.service;

import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.audit.domain.AdminAuditLog;
import com.waibao.pingdian.audit.repo.AdminAuditLogRepository;
import com.waibao.pingdian.audit.web.dto.AuditLogItem;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 后台写操作与材料读取审计。
 */
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository repository;

    public AdminAuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID actorId, AdminRole actorRole, String action, String targetType,
                       String targetId, String reasonCode, String detail, String ticketId, String ip) {
        record(actorId, actorRole, action, targetType, targetId, reasonCode, detail,
                ticketId, ip, null, null);
    }

    public void record(UUID actorId, AdminRole actorRole, String action, String targetType,
                       String targetId, String reasonCode, String detail, String ticketId, String ip,
                       String beforeHash, String afterHash) {
        AdminAuditLog log = new AdminAuditLog();
        log.setActorId(actorId);
        log.setActorRole(actorRole);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setReasonCode(reasonCode);
        log.setDetail(detail);
        log.setTicketId(ticketId);
        log.setIp(ip);
        log.setBeforeHash(beforeHash);
        log.setAfterHash(afterHash);
        repository.save(log);
    }

    /**
     * 审计日志查询：action/targetType 支持前缀匹配，按 created_at 降序。
     */
    public PageResult<AuditLogItem> list(UUID actorId, String action, String targetType,
                                        String targetId, String from, String to, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Instant fromTime = parseTime(from, false);
        Instant toTime = parseTime(to, true);

        Specification<AdminAuditLog> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.like(root.get("action"), action.trim() + "%"));
            }
            if (targetType != null && !targetType.isBlank()) {
                predicates.add(cb.like(root.get("targetType"), targetType.trim() + "%"));
            }
            if (targetId != null && !targetId.isBlank()) {
                predicates.add(cb.equal(root.get("targetId"), targetId.trim()));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toTime));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdminAuditLog> result = repository.findAll(specification, pageable);
        List<AuditLogItem> items = result.getContent().stream()
                .map(log -> new AuditLogItem(
                        log.getId(),
                        log.getActorId(),
                        log.getActorRole(),
                        log.getAction(),
                        log.getTargetType(),
                        log.getTargetId(),
                        log.getReasonCode(),
                        log.getDetail(),
                        log.getBeforeHash(),
                        log.getAfterHash(),
                        log.getIp(),
                        log.getTicketId(),
                        log.getCreatedAt()))
                .toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    private Instant parseTime(String raw, boolean endOfDay) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            // 回退到日期格式。
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return (endOfDay ? date.plusDays(1).atStartOfDay(ZoneOffset.UTC)
                    : date.atStartOfDay(ZoneOffset.UTC)).toInstant();
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "时间格式不正确，应为 ISO 日期或时间");
        }
    }
}
