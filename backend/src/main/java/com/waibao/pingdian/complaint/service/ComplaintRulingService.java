package com.waibao.pingdian.complaint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.complaint.config.ComplaintProperties;
import com.waibao.pingdian.complaint.domain.Complaint;
import com.waibao.pingdian.complaint.domain.ComplaintEvidence;
import com.waibao.pingdian.complaint.domain.ComplaintParty;
import com.waibao.pingdian.complaint.domain.ComplaintResult;
import com.waibao.pingdian.complaint.domain.ComplaintStatus;
import com.waibao.pingdian.complaint.domain.EvidenceGrade;
import com.waibao.pingdian.complaint.repo.ComplaintEvidenceRepository;
import com.waibao.pingdian.complaint.repo.ComplaintRepository;
import com.waibao.pingdian.complaint.web.dto.ComplaintRulingRequest;
import com.waibao.pingdian.complaint.web.dto.RulingResult;
import com.waibao.pingdian.notification.domain.NotificationChannel;
import com.waibao.pingdian.notification.domain.NotificationType;
import com.waibao.pingdian.notification.service.NotificationService;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.review.service.ReviewStateMachine;
import com.waibao.pingdian.security.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 投诉裁定：证据分级矩阵、强制双人复核、超时自动判定与最终落地。
 * 首次需复核时只保存提案（不改点评状态），第二次须另一名 SUPER_ADMIN 确认（AC-20）。
 */
@Service
public class ComplaintRulingService {

    private static final int MAX_REASON = 2000;
    private static final int MAX_CLAUSE = 200;
    private static final int MAX_CITED = 50;

    private final ComplaintRepository complaintRepository;
    private final ComplaintEvidenceRepository evidenceRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewStateMachine stateMachine;
    private final NotificationService notificationService;
    private final AdminAuditService auditService;
    private final ComplaintProperties properties;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public ComplaintRulingService(ComplaintRepository complaintRepository,
                                  ComplaintEvidenceRepository evidenceRepository,
                                  ReviewRepository reviewRepository,
                                  ReviewStateMachine stateMachine,
                                  NotificationService notificationService,
                                  AdminAuditService auditService,
                                  ComplaintProperties properties,
                                  CryptoService cryptoService,
                                  ObjectMapper objectMapper) {
        this.complaintRepository = complaintRepository;
        this.evidenceRepository = evidenceRepository;
        this.reviewRepository = reviewRepository;
        this.stateMachine = stateMachine;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.properties = properties;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RulingResult rule(UUID complaintId, ComplaintRulingRequest request,
                             UUID adminId, AdminRole adminRole, String ip) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投诉工单不存在"));
        if (complaint.getStatus() != ComplaintStatus.EVIDENCE_COLLECTING
                && complaint.getStatus() != ComplaintStatus.PENDING_RULING) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前投诉状态不可裁定");
        }
        boolean confirmation = complaint.getStatus() == ComplaintStatus.PENDING_RULING
                && complaint.getProposedBy() != null;

        String rawResult = request == null ? null : request.result();
        if (rawResult == null || rawResult.isBlank()) {
            rawResult = confirmation ? complaint.getProposedResolution() : null;
        }
        if (rawResult == null || rawResult.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写裁定结论 result");
        }
        ComplaintResult result = parseResult(rawResult);

        EvidenceGrade enterpriseGrade = request != null && request.enterpriseGrade() != null
                ? parseGrade(request.enterpriseGrade())
                : storedGrade(complaint.getEnterpriseGrade());
        EvidenceGrade userGrade = request != null && request.userGrade() != null
                ? parseGrade(request.userGrade())
                : storedGrade(complaint.getUserGrade());
        validateMatrix(enterpriseGrade, userGrade, result);

        String reasonText = request == null ? null : request.reasonText();
        if (reasonText == null || reasonText.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写裁定理由 reasonText");
        }
        reasonText = reasonText.trim();
        if (reasonText.length() > MAX_REASON) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "裁定理由不能超过 " + MAX_REASON + " 字");
        }
        String ruleClause = request == null ? null : request.ruleClause();
        if (ruleClause != null) {
            ruleClause = ruleClause.trim();
            if (ruleClause.length() > MAX_CLAUSE) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "适用条款不能超过 " + MAX_CLAUSE + " 字");
            }
            if (ruleClause.isEmpty()) {
                ruleClause = null;
            }
        }
        List<String> cited = normalizeCited(request == null ? null : request.citedMaterialIds());

        Review review = reviewRepository.findById(complaint.getReviewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));

        boolean dualRequired = confirmation
                || (request != null && Boolean.TRUE.equals(request.requiresDualReview()))
                || requiresDualReview(complaint, review, result);

        if (!confirmation && dualRequired) {
            complaint.setDualReviewRequired(true);
            complaint.setProposedResolution(result.name());
            complaint.setProposedBy(adminId);
            complaint.setProposedAt(Instant.now());
            complaint.setEnterpriseGrade(enterpriseGrade.persisted());
            complaint.setUserGrade(userGrade.persisted());
            complaint.setStatus(ComplaintStatus.PENDING_RULING);
            complaintRepository.save(complaint);

            Map<String, Object> detail = auditDetail(complaint, result, enterpriseGrade, userGrade,
                    reasonText, cited, ruleClause, true, "PROPOSED");
            auditService.record(adminId, adminRole, "RULING_PROPOSED", "COMPLAINT",
                    complaintId.toString(), result.name(), json(detail), complaintId.toString(), ip);

            return new RulingResult(complaintId, complaint.getStatus(), result.name(),
                    true, isOverdue(complaint), rulingDeadline(complaint));
        }

        if (confirmation) {
            if (adminRole != AdminRole.SUPER_ADMIN) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "强制双人复核必须由超级管理员确认");
            }
            if (adminId.equals(complaint.getProposedBy())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "双人复核不得由同一名管理员确认");
            }
            complaint.setDualReviewBy(adminId);
            complaint.setDualReviewAt(Instant.now());
        }

        finalizeRuling(complaint, review, result, enterpriseGrade, userGrade, reasonText,
                cited, ruleClause, dualRequired, adminId, adminRole, ip);
        return new RulingResult(complaintId, complaint.getStatus(), result.name(),
                false, isOverdue(complaint), rulingDeadline(complaint));
    }

    /**
     * 超时判定：双方无证据/仅 C → INSUFFICIENT；仅一方有 A/B → 该方胜；双方均有 A/B 倾向保留。
     * 由管理员在截止后调用；Task 11 的调度器可直接复用。
     */
    @Transactional
    public RulingResult rulingAfterDeadline(UUID complaintId, UUID adminId, AdminRole adminRole, String ip) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投诉工单不存在"));
        EvidenceGrade enterpriseGrade = bestGrade(complaintId, ComplaintParty.ENTERPRISE);
        EvidenceGrade userGrade = bestGrade(complaintId, ComplaintParty.USER);

        ComplaintResult result;
        boolean enterpriseStrong = enterpriseGrade == EvidenceGrade.A || enterpriseGrade == EvidenceGrade.B;
        boolean userStrong = userGrade == EvidenceGrade.A || userGrade == EvidenceGrade.B;
        if (enterpriseStrong && userStrong) {
            // 双方均有强/中证据时人工逐条比对；自动超时判定倾向保留。
            result = ComplaintResult.USER_WIN;
        } else if (enterpriseStrong) {
            result = ComplaintResult.COMPANY_WIN;
        } else if (userStrong) {
            result = ComplaintResult.USER_WIN;
        } else {
            result = ComplaintResult.INSUFFICIENT;
        }

        List<String> cited = evidenceRepository.findByComplaintIdOrderByCreatedAtAsc(complaintId).stream()
                .map(e -> e.getId().toString())
                .toList();
        ComplaintRulingRequest request = new ComplaintRulingRequest(result.name(),
                enterpriseGrade.name(), userGrade.name(),
                "举证截止，双方超时未补交或仅提交弱证据，按证据分级矩阵自动判定。",
                cited, "PRD 8.5 判定矩阵", false, null);
        return rule(complaintId, request, adminId, adminRole, ip);
    }

    // ------------------------------------------------------------------
    // 矩阵与复核条件
    // ------------------------------------------------------------------

    private void validateMatrix(EvidenceGrade enterprise, EvidenceGrade user, ComplaintResult result) {
        Set<ComplaintResult> allowed = allowedResults(enterprise, user);
        if (!allowed.contains(result)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "裁定结论与证据分级矩阵不一致：" + describe(enterprise, user) + " 仅允许 " + allowed);
        }
    }

    static Set<ComplaintResult> allowedResults(EvidenceGrade enterprise, EvidenceGrade user) {
        boolean eStrong = enterprise == EvidenceGrade.A || enterprise == EvidenceGrade.B;
        boolean uStrong = user == EvidenceGrade.A || user == EvidenceGrade.B;
        boolean eWeak = enterprise == EvidenceGrade.C || enterprise == EvidenceGrade.NONE;
        boolean uWeak = user == EvidenceGrade.C || user == EvidenceGrade.NONE;

        if (eWeak && uWeak) {
            return EnumSet.of(ComplaintResult.INSUFFICIENT);
        }
        // 企业 A/B，用户仅 C 或未提交；用户 A/B，企业仅 C 或未提交。
        if (eStrong && uWeak) {
            return EnumSet.of(ComplaintResult.COMPANY_WIN);
        }
        if (uStrong && eWeak) {
            return EnumSet.of(ComplaintResult.USER_WIN);
        }
        // 双方均有强/中证据：人工逐条比对，倾向保留，但允许两种结论。
        return EnumSet.of(ComplaintResult.USER_WIN, ComplaintResult.COMPANY_WIN);
    }

    private String describe(EvidenceGrade enterprise, EvidenceGrade user) {
        return "企业=" + enterprise + ", 用户=" + user;
    }

    private boolean requiresDualReview(Complaint complaint, Review review, ComplaintResult result) {
        if (complaint.isAmountFlag() || complaint.isSafetyFlag()) {
            return true;
        }
        Instant since = Instant.now().minus(properties.getSameCompanyWindowDays(), ChronoUnit.DAYS);
        if (complaintRepository.countRecentByClaimantUscc(complaint.getClaimantUscc(), since)
                >= properties.getSameCompanyThreshold()) {
            return true;
        }
        if (result == ComplaintResult.COMPANY_WIN && review.getPublishedAt() != null
                && review.getPublishedAt().isBefore(
                Instant.now().minus(properties.getRemovalAgeDays(), ChronoUnit.DAYS))) {
            return true;
        }
        return false;
    }

    private void finalizeRuling(Complaint complaint, Review review, ComplaintResult result,
                                EvidenceGrade enterpriseGrade, EvidenceGrade userGrade,
                                String reasonText, List<String> cited, String ruleClause,
                                boolean dualRequired, UUID adminId, AdminRole adminRole, String ip) {
        ReviewStatus target = switch (result) {
            case USER_WIN -> ReviewStatus.PUBLISHED;
            case COMPANY_WIN -> ReviewStatus.REMOVED;
            case INSUFFICIENT -> ReviewStatus.FOLDED;
        };
        if (!ReviewStateMachine.canTransition(review.getStatus(), target)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "点评当前状态不可迁移到 " + target);
        }

        String beforeHash = sha256(complaint.getStatus() + "|" + review.getStatus()
                + "|" + review.getTotalScore() + "|" + review.getContent());

        complaint.setStatus(result.toStatus());
        complaint.setResolution(result.name());
        complaint.setResolvedBy(adminId);
        complaint.setEnterpriseGrade(enterpriseGrade.persisted());
        complaint.setUserGrade(userGrade.persisted());
        complaint.setRulingReason(reasonText);
        complaint.setCitedMaterials(jsonArray(cited));
        complaint.setRuleClause(ruleClause);
        if (dualRequired) {
            complaint.setDualReviewRequired(true);
            complaint.setDualReviewBy(adminId);
            complaint.setDualReviewAt(Instant.now());
        }
        complaintRepository.save(complaint);

        Map<String, Object> detail = auditDetail(complaint, result, enterpriseGrade, userGrade,
                reasonText, cited, ruleClause, dualRequired, "FINAL");
        stateMachine.transition(review, target, ReviewActorType.ADMIN, adminId,
                "COMPLAINT_RULING", "COMPLAINT_RULING", result.name(), json(detail));

        String afterHash = sha256(complaint.getStatus() + "|" + review.getStatus()
                + "|" + review.getTotalScore() + "|" + review.getContent());
        auditService.record(adminId, adminRole, "RULING_FINAL", "COMPLAINT",
                complaint.getId().toString(), result.name(), json(detail),
                complaint.getId().toString(), ip, beforeHash, afterHash);

        notificationService.send(review.getUserId(), NotificationType.RULING_RESULT, "投诉裁定结果",
                rulingMessage(result), NotificationChannel.BOTH, "COMPLAINT",
                complaint.getId().toString());
        notifyEnterprise(complaint, "RULING_RESULT", Map.of(
                "complaintId", complaint.getId().toString(), "result", result.name()));
    }

    private String rulingMessage(ComplaintResult result) {
        return switch (result) {
            case USER_WIN -> "企业投诉未获支持，评价继续展示。";
            case COMPANY_WIN -> "该评价经核实存在不实内容，已下架。";
            case INSUFFICIENT -> "争议未决，双方举证不足，评价已折叠。";
        };
    }

    private Map<String, Object> auditDetail(Complaint complaint, ComplaintResult result,
                                            EvidenceGrade enterpriseGrade, EvidenceGrade userGrade,
                                            String reasonText, List<String> cited, String ruleClause,
                                            boolean dualRequired, String phase) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("phase", phase);
        detail.put("reviewId", complaint.getReviewId().toString());
        detail.put("result", result.name());
        detail.put("enterpriseGrade", enterpriseGrade.name());
        detail.put("userGrade", userGrade.name());
        detail.put("reasonText", reasonText);
        detail.put("citedMaterialIds", cited);
        detail.put("ruleClause", ruleClause);
        detail.put("dualReviewRequired", dualRequired);
        detail.put("overdue", isOverdue(complaint));
        return detail;
    }

    private EvidenceGrade bestGrade(UUID complaintId, ComplaintParty side) {
        EvidenceGrade best = EvidenceGrade.NONE;
        for (ComplaintEvidence evidence : evidenceRepository.findByComplaintIdAndSide(complaintId, side)) {
            if ("DELETED".equals(evidence.getMaterialStatus()) || evidence.getEvidenceLevel() == null) {
                continue;
            }
            EvidenceGrade grade = storedGrade(evidence.getEvidenceLevel());
            if (rank(grade) > rank(best)) {
                best = grade;
            }
        }
        return best;
    }

    private static int rank(EvidenceGrade grade) {
        return switch (grade) {
            case A -> 3;
            case B -> 2;
            case C -> 1;
            case NONE -> 0;
        };
    }

    private static EvidenceGrade storedGrade(String value) {
        if (value == null || value.isBlank()) {
            return EvidenceGrade.NONE;
        }
        return EvidenceGrade.from(value);
    }

    private ComplaintResult parseResult(String raw) {
        try {
            return ComplaintResult.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "裁定结论必须为 USER_WIN、COMPANY_WIN 或 INSUFFICIENT");
        }
    }

    private EvidenceGrade parseGrade(String raw) {
        try {
            return EvidenceGrade.from(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "证据等级必须为 A/B/C/NONE");
        }
    }

    private List<String> normalizeCited(List<String> cited) {
        List<String> result = new ArrayList<>();
        if (cited == null) {
            return result;
        }
        if (cited.size() > MAX_CITED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "引用材料不能超过 " + MAX_CITED + " 个");
        }
        for (String id : cited) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String value = id.trim();
            if (value.length() > 64) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "引用材料编号过长");
            }
            result.add(value);
        }
        return result;
    }

    private boolean isOverdue(Complaint complaint) {
        return Instant.now().isAfter(rulingDeadline(complaint));
    }

    private Instant rulingDeadline(Complaint complaint) {
        Instant base = complaint.getDeadline() != null ? complaint.getDeadline() : complaint.getCreatedAt();
        return base.plus(properties.getRulingTargetHours(), ChronoUnit.HOURS);
    }

    private void notifyEnterprise(Complaint complaint, String template, Map<String, String> params) {
        try {
            notificationService.sendSms(cryptoService.decrypt(complaint.getClaimantContactCipher()),
                    template, params);
        } catch (RuntimeException ignored) {
            // 联系方式异常不阻断裁定落地。
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String jsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("哈希计算失败", ex);
        }
    }
}
