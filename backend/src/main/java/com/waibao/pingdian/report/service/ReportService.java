package com.waibao.pingdian.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.service.ContributionService;
import com.waibao.pingdian.audit.repo.AdminAuditLogRepository;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.repo.CompanyRepository;
import com.waibao.pingdian.report.config.ReportProperties;
import com.waibao.pingdian.report.domain.Report;
import com.waibao.pingdian.report.domain.ReportReasonCode;
import com.waibao.pingdian.report.domain.ReportStatus;
import com.waibao.pingdian.report.domain.ReportTargetType;
import com.waibao.pingdian.report.repo.ReportRepository;
import com.waibao.pingdian.report.web.dto.ReportCreateRequest;
import com.waibao.pingdian.report.web.dto.ReportItemView;
import com.waibao.pingdian.report.web.dto.ReportResolveRequest;
import com.waibao.pingdian.report.web.dto.ReportSubmitView;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewAudit;
import com.waibao.pingdian.review.repo.ReviewAuditRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.security.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 举报提交、自动进人工标记与管理端处置（R15）。
 * 举报不直接改变点评状态；有效举报仅发放 +20 贡献值（幂等）。
 */
@Service
public class ReportService {

    private static final int MAX_DETAIL_LENGTH = 500;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final Duration REPORT_WINDOW = Duration.ofHours(24);
    private static final UUID SYSTEM_ACTOR_ID = new UUID(0L, 0L);
    private static final String THRESHOLD_ACTION = "REPORT_THRESHOLD";

    private final ReportRepository reportRepository;
    private final ReviewRepository reviewRepository;
    private final CompanyRepository companyRepository;
    private final ReviewAuditRepository reviewAuditRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final AdminAuditService adminAuditService;
    private final ContributionService contributionService;
    private final CurrentUserService currentUserService;
    private final ReportProperties reportProperties;
    private final ObjectMapper objectMapper;

    public ReportService(ReportRepository reportRepository,
                         ReviewRepository reviewRepository,
                         CompanyRepository companyRepository,
                         ReviewAuditRepository reviewAuditRepository,
                         AdminAuditLogRepository adminAuditLogRepository,
                         AdminAuditService adminAuditService,
                         ContributionService contributionService,
                         CurrentUserService currentUserService,
                         ReportProperties reportProperties,
                         ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.reviewRepository = reviewRepository;
        this.companyRepository = companyRepository;
        this.reviewAuditRepository = reviewAuditRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.adminAuditService = adminAuditService;
        this.contributionService = contributionService;
        this.currentUserService = currentUserService;
        this.reportProperties = reportProperties;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 用户举报
    // ------------------------------------------------------------------

    @Transactional
    public ReportSubmitView submit(ReportCreateRequest request) {
        UUID userId = currentUserService.getUserId();
        ReportTargetType targetType = parseTargetType(request == null ? null : request.targetType());
        Target target = resolveTarget(targetType, request == null ? null : request.targetId());
        ReportReasonCode reasonCode = parseReason(request == null ? null : request.reasonCode());
        String detail = normalizeDetail(request == null ? null : request.detail());

        Instant now = Instant.now();
        List<Report> recent = reportRepository
                .findByUserIdAndTargetTypeAndTargetIdAndCreatedAtAfterOrderByCreatedAtAsc(
                        userId, target.type(), target.id(), now.minus(REPORT_WINDOW));
        int limit = Math.max(1, reportProperties.getPerTarget24h());
        if (recent.size() >= limit) {
            Report last = recent.get(recent.size() - 1);
            Instant availableAt = last.getCreatedAt().plus(REPORT_WINDOW);
            long remainingSeconds = Math.max(0,
                    Duration.between(now, availableAt).getSeconds());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("availableAt", availableAt);
            details.put("remainingSeconds", remainingSeconds);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "同一对象 24 小时内仅可举报一次，可举报时间：" + availableAt, details);
        }

        Report report = new Report();
        report.setTargetType(target.type());
        report.setTargetId(target.id());
        report.setUserId(userId);
        report.setReasonCode(reasonCode);
        report.setDetail(detail);
        report.setStatus(ReportStatus.PENDING);
        reportRepository.saveAndFlush(report);

        long distinctUsers = reportRepository.countDistinctUsersByTarget(target.type(), target.id());
        boolean thresholdReached = distinctUsers >= Math.max(1, reportProperties.getAutoManualThreshold());
        if (thresholdReached) {
            markThreshold(target, (int) distinctUsers);
        }
        return new ReportSubmitView(report.getId(), report.getStatus(), thresholdReached);
    }

    // ------------------------------------------------------------------
    // 管理端
    // ------------------------------------------------------------------

    public PageResult<ReportItemView> list(String rawStatus, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<Report> result;
        if (rawStatus == null || rawStatus.isBlank()) {
            result = reportRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            ReportStatus status;
            try {
                status = ReportStatus.valueOf(rawStatus.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "举报状态不正确");
            }
            result = reportRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        }

        Map<String, Long> thresholdCache = new HashMap<>();
        int threshold = Math.max(1, reportProperties.getAutoManualThreshold());
        List<ReportItemView> items = result.getContent().stream()
                .map(report -> new ReportItemView(
                        report.getId(),
                        report.getTargetType(),
                        report.getTargetId(),
                        report.getReasonCode().name(),
                        report.getDetail(),
                        report.getStatus(),
                        report.getCreatedAt(),
                        thresholdCache.computeIfAbsent(keyOf(report), key ->
                                reportRepository.countDistinctUsersByTarget(
                                        report.getTargetType(), report.getTargetId())
                        ) >= threshold))
                .toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    @Transactional
    public void resolve(UUID reportId, ReportResolveRequest request,
                        UUID adminId, AdminRole adminRole, String ip) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "举报不存在"));
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "该举报已处置");
        }
        String raw = request == null ? null : request.resolvedValue();
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写处置结果");
        }
        ReportStatus target;
        try {
            target = ReportStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "处置结果不正确");
        }
        if (target == ReportStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "处置结果不正确");
        }
        String note = normalizeNote(request == null ? null : request.note());

        report.setStatus(target);
        reportRepository.saveAndFlush(report);

        if (target == ReportStatus.VALID) {
            contributionService.awardValidReport(report.getUserId(), report.getId());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", target.name());
        detail.put("note", note);
        detail.put("targetType", report.getTargetType().name());
        detail.put("targetId", report.getTargetId().toString());
        adminAuditService.record(adminId, adminRole, "REPORT_RESOLVE", "REPORT",
                reportId.toString(), target.name(), json(detail), null, ip);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void markThreshold(Target target, int distinctUsers) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reportCount", distinctUsers);
        detail.put("threshold", reportProperties.getAutoManualThreshold());
        detail.put("source", "SYSTEM");
        if (target.type() == ReportTargetType.REVIEW) {
            if (!reviewAuditRepository.existsByReviewIdAndAction(target.id(), THRESHOLD_ACTION)) {
                ReviewAudit audit = new ReviewAudit();
                audit.setReviewId(target.id());
                audit.setStage("REPORT");
                audit.setActorType(ReviewActorType.SYSTEM);
                audit.setAction(THRESHOLD_ACTION);
                audit.setDetail(json(detail));
                reviewAuditRepository.save(audit);
            }
            return;
        }
        if (!adminAuditLogRepository.existsByActionAndTargetTypeAndTargetId(
                THRESHOLD_ACTION, ReportTargetType.COMPANY.name(), target.id().toString())) {
            adminAuditService.record(SYSTEM_ACTOR_ID, AdminRole.CONTENT_ADMIN,
                    THRESHOLD_ACTION, ReportTargetType.COMPANY.name(), target.id().toString(),
                    THRESHOLD_ACTION, json(detail), null, null);
        }
    }

    private Target resolveTarget(ReportTargetType type, String rawTargetId) {
        if (rawTargetId == null || rawTargetId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "targetId 不能为空");
        }
        String value = rawTargetId.trim();
        if (type == ReportTargetType.REVIEW) {
            UUID id = parseUuid(value, "点评 id 格式不正确");
            if (!reviewRepository.existsById(id)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "点评不存在");
            }
            return new Target(type, id);
        }
        // report.target_id 为 uuid：公司举报接受 USCC，并映射为稳定的 UUID；也兼容直接传 UUID。
        if (looksLikeUuid(value)) {
            return new Target(type, UUID.fromString(value));
        }
        String uscc = value.toUpperCase();
        if (!companyRepository.existsById(uscc)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "公司档案不存在");
        }
        return new Target(type, companyUuid(uscc));
    }

    private static UUID companyUuid(String uscc) {
        return UUID.nameUUIDFromBytes(("waibao-company:" + uscc).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean looksLikeUuid(String value) {
        return value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    private static UUID parseUuid(String value, String message) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    private static ReportTargetType parseTargetType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "targetType 不能为空");
        }
        try {
            return ReportTargetType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "targetType 不正确");
        }
    }

    private static ReportReasonCode parseReason(String raw) {
        try {
            return ReportReasonCode.from(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reasonCode 不正确");
        }
    }

    private static String normalizeDetail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_DETAIL_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "举报说明不能超过 " + MAX_DETAIL_LENGTH + " 字");
        }
        return value;
    }

    private static String normalizeNote(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "处置说明不能超过 " + MAX_NOTE_LENGTH + " 字");
        }
        return value;
    }

    private static String keyOf(Report report) {
        return report.getTargetType().name() + ":" + report.getTargetId();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private record Target(ReportTargetType type, UUID id) {
    }
}
