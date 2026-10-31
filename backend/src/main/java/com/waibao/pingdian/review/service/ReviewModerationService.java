package com.waibao.pingdian.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.domain.AnonymousIdentity;
import com.waibao.pingdian.account.repo.AnonymousIdentityRepository;
import com.waibao.pingdian.account.service.ContributionService;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.domain.Company;
import com.waibao.pingdian.notification.domain.NotificationChannel;
import com.waibao.pingdian.notification.domain.NotificationType;
import com.waibao.pingdian.notification.service.NotificationService;
import com.waibao.pingdian.company.repo.CompanyRepository;
import com.waibao.pingdian.review.config.ReviewProperties;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewAudit;
import com.waibao.pingdian.review.domain.ReviewDimension;
import com.waibao.pingdian.review.domain.ReviewModerationAction;
import com.waibao.pingdian.review.domain.ReviewRejectReasonCode;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewAuditRepository;
import com.waibao.pingdian.review.repo.ReviewDimensionRepository;
import com.waibao.pingdian.review.repo.ReviewEvidenceRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.review.web.dto.ReasonTemplateBundle;
import com.waibao.pingdian.review.web.dto.ReasonTemplateView;
import com.waibao.pingdian.review.web.dto.ReviewAuditView;
import com.waibao.pingdian.review.web.dto.ReviewBatchDispositionRequest;
import com.waibao.pingdian.review.web.dto.ReviewBatchDispositionResult;
import com.waibao.pingdian.review.web.dto.ReviewDispositionResult;
import com.waibao.pingdian.review.web.dto.ReviewEvidenceMaterialView;
import com.waibao.pingdian.review.web.dto.ReviewItemView;
import com.waibao.pingdian.review.web.dto.ReviewQueueDetail;
import com.waibao.pingdian.review.web.dto.ReviewQueueItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 人工审核队列、审核处置与批量处置（R11 / AC-06 / AC-07 / AC-15）。
 * 所有处置走 ReviewStateMachine 校验，状态变更与公司聚合重算在同一事务内完成。
 */
@Service
public class ReviewModerationService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 100;
    private static final long OVERDUE_HOURS = 48;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final int MAX_REASON_LENGTH = 40;

    private static final String SOURCE_AI_LOW_CONFIDENCE = "AI_LOW_CONFIDENCE";
    private static final String SOURCE_SPECIFIC_CLAIM = "SPECIFIC_CLAIM";
    private static final String SOURCE_REPORTED = "REPORTED";
    private static final String SOURCE_COPY_DETECTED = "COPY_DETECTED";
    private static final String SOURCE_RISK_FLAGGED = "RISK_FLAGGED";
    private static final String SOURCE_APPEAL = "APPEAL";

    private final ReviewRepository reviewRepository;
    private final ReviewDimensionRepository dimensionRepository;
    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewAuditRepository auditRepository;
    private final CompanyRepository companyRepository;
    private final AnonymousIdentityRepository identityRepository;
    private final ReviewStateMachine stateMachine;
    private final ContributionService contributionService;
    private final AdminAuditService adminAuditService;
    private final NotificationService notificationService;
    private final ReviewProperties reviewProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTemplate;

    public ReviewModerationService(ReviewRepository reviewRepository,
                                   ReviewDimensionRepository dimensionRepository,
                                   ReviewEvidenceRepository evidenceRepository,
                                   ReviewAuditRepository auditRepository,
                                   CompanyRepository companyRepository,
                                   AnonymousIdentityRepository identityRepository,
                                   ReviewStateMachine stateMachine,
                                   ContributionService contributionService,
                                   AdminAuditService adminAuditService,
                                   NotificationService notificationService,
                                   ReviewProperties reviewProperties,
                                   ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.dimensionRepository = dimensionRepository;
        this.evidenceRepository = evidenceRepository;
        this.auditRepository = auditRepository;
        this.companyRepository = companyRepository;
        this.identityRepository = identityRepository;
        this.stateMachine = stateMachine;
        this.contributionService = contributionService;
        this.adminAuditService = adminAuditService;
        this.notificationService = notificationService;
        this.reviewProperties = reviewProperties;
        this.objectMapper = objectMapper;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------
    // 队列
    // ------------------------------------------------------------------

    public PageResult<ReviewQueueItem> queue(String source, String keyword, int page, int size) {
        String normalizedSource = normalizeSource(source);
        String normalizedKeyword = keyword == null ? null : keyword.trim().toLowerCase();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        List<ReviewQueueItem> items = new ArrayList<>();
        for (Review review : reviewRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.PENDING_MANUAL)) {
            QueueMeta meta = metaOf(review);
            if (normalizedSource != null && !normalizedSource.equals(meta.source())) {
                continue;
            }
            ReviewQueueItem item = toQueueItem(review, meta);
            if (normalizedKeyword != null && !normalizedKeyword.isEmpty()
                    && !matchesKeyword(item, normalizedKeyword)) {
                continue;
            }
            items.add(item);
        }
        items.sort(Comparator.comparing(ReviewQueueItem::enteredQueueAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReviewQueueItem::reviewId));

        int from = Math.min((safePage - 1) * safeSize, items.size());
        int to = Math.min(from + safeSize, items.size());
        List<ReviewQueueItem> pageItems = from >= to ? List.of() : items.subList(from, to);
        return PageResult.of(pageItems, safePage, safeSize, items.size());
    }

    public ReviewQueueDetail detail(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
        return toDetail(review);
    }

    // ------------------------------------------------------------------
    // 处置
    // ------------------------------------------------------------------

    @Transactional
    public void approve(UUID reviewId, String reasonCode, String note,
                        UUID adminId, AdminRole adminRole, String ip) {
        String code = requireReason(reasonCode);
        String message = requireNote(note);
        disposeInternal(reviewId, ReviewModerationAction.APPROVE, code, message, adminId, adminRole, ip);
    }

    @Transactional
    public void fold(UUID reviewId, String reasonCode, String note,
                     UUID adminId, AdminRole adminRole, String ip) {
        String code = requireReason(reasonCode);
        String message = requireNote(note);
        disposeInternal(reviewId, ReviewModerationAction.FOLD, code, message, adminId, adminRole, ip);
    }

    @Transactional
    public void reject(UUID reviewId, String reasonCode, String note,
                       UUID adminId, AdminRole adminRole, String ip) {
        String code = requireRejectReason(reasonCode).name();
        String message = requireNote(note);
        disposeInternal(reviewId, ReviewModerationAction.REJECT, code, message, adminId, adminRole, ip);
    }

    /**
     * 批量处置：每条使用独立事务，部分失败不回滚 successful 条目。
     */
    public ReviewBatchDispositionResult batch(ReviewBatchDispositionRequest request,
                                              UUID adminId, AdminRole adminRole, String ip) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "ids 不能为空");
        }
        if (request.ids().size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "单次最多处置 " + MAX_BATCH_SIZE + " 条");
        }
        ReviewModerationAction action = parseAction(request.action());
        String reasonCode = action == ReviewModerationAction.REJECT
                ? requireRejectReason(request.reasonCode()).name()
                : requireReason(request.reasonCode());
        String note = requireNote(request.note());

        List<ReviewDispositionResult> results = new ArrayList<>();
        int succeeded = 0;
        for (UUID reviewId : request.ids()) {
            ReviewDispositionResult result;
            try {
                result = requiresNewTemplate.execute(status -> {
                    ReviewStatus target = disposeInternal(reviewId, action, reasonCode, note,
                            adminId, adminRole, ip);
                    return new ReviewDispositionResult(reviewId, true, target.name(), 0, "OK");
                });
            } catch (BusinessException ex) {
                result = new ReviewDispositionResult(reviewId, false, null,
                        ex.getErrorCode().getCode(), ex.getMessage());
            } catch (Exception ex) {
                result = new ReviewDispositionResult(reviewId, false, null,
                        ErrorCode.INTERNAL_ERROR.getCode(), "处置失败");
            }
            if (result != null && result.success()) {
                succeeded++;
            }
            if (result != null) {
                results.add(result);
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action.name());
        detail.put("reasonCode", reasonCode);
        detail.put("note", note);
        detail.put("total", results.size());
        detail.put("succeeded", succeeded);
        detail.put("failed", results.size() - succeeded);
        adminAuditService.record(adminId, adminRole, "REVIEW_BATCH", "REVIEW",
                joinIds(request.ids()), reasonCode, json(detail), null, ip,
                sha256(joinIds(request.ids())), sha256(joinIds(request.ids()) + "|" + succeeded));
        return new ReviewBatchDispositionResult(results.size(), succeeded,
                results.size() - succeeded, results);
    }

    public ReasonTemplateBundle reasonTemplates() {
        return new ReasonTemplateBundle(
                List.of(
                        new ReasonTemplateView("CONTENT_COMPLIANT", "内容合规，证据充分，予以通过"),
                        new ReasonTemplateView("EVIDENCE_SUFFICIENT", "佐证材料充分，符合社区规范"),
                        new ReasonTemplateView("FACT_VERIFIED", "事实描述清晰，可公开发布"),
                        new ReasonTemplateView("MANUAL_CONFIRMED", "人工复核确认无违规内容")),
                List.of(
                        new ReasonTemplateView("INSUFFICIENT_EVIDENCE", "证据不足，折叠展示"),
                        new ReasonTemplateView("VAGUE_CLAIM", "描述笼统，缺乏可核实依据"),
                        new ReasonTemplateView("UNVERIFIABLE", "无法核实具体事实，折叠展示"),
                        new ReasonTemplateView("LOW_INFORMATION", "信息量不足，折叠展示")),
                List.of(
                        new ReasonTemplateView(ReviewRejectReasonCode.INSUFFICIENT_EVIDENCE.name(),
                                ReviewRejectReasonCode.INSUFFICIENT_EVIDENCE.message()),
                        new ReasonTemplateView(ReviewRejectReasonCode.FALSE_CLAIM.name(),
                                ReviewRejectReasonCode.FALSE_CLAIM.message()),
                        new ReasonTemplateView(ReviewRejectReasonCode.ABUSIVE_LANGUAGE.name(),
                                ReviewRejectReasonCode.ABUSIVE_LANGUAGE.message()),
                        new ReasonTemplateView(ReviewRejectReasonCode.ADVERTISING.name(),
                                ReviewRejectReasonCode.ADVERTISING.message()),
                        new ReasonTemplateView(ReviewRejectReasonCode.PRIVACY.name(),
                                ReviewRejectReasonCode.PRIVACY.message()),
                        new ReasonTemplateView(ReviewRejectReasonCode.OTHER.name(),
                                ReviewRejectReasonCode.OTHER.message())));
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private ReviewStatus disposeInternal(UUID reviewId, ReviewModerationAction action,
                                         String reasonCode, String note,
                                         UUID adminId, AdminRole adminRole, String ip) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
        if (review.getStatus() != ReviewStatus.PENDING_MANUAL) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅待人工点评可以处置");
        }
        String beforeHash = snapshotHash(review);

        ReviewStatus target;
        String reviewDetail;
        if (action == ReviewModerationAction.APPROVE) {
            if (review.getPublishedAt() == null) {
                Instant now = Instant.now();
                review.setPublishedAt(now);
                review.setEditDeadline(now.plus(reviewProperties.getEditWindowDays(), ChronoUnit.DAYS));
            }
            reviewRepository.saveAndFlush(review);
            reviewDetail = json(reviewAuditDetail(review, reasonCode, note, null, false));
            stateMachine.transition(review, ReviewStatus.PUBLISHED, ReviewActorType.ADMIN, adminId,
                    "APPROVE", "ADMIN_REVIEW", reasonCode, reviewDetail);
            contributionService.awardReviewPublished(review.getUserId(), review.getId());
            target = ReviewStatus.PUBLISHED;
        } else if (action == ReviewModerationAction.FOLD) {
            reviewDetail = json(reviewAuditDetail(review, reasonCode, note, null, false));
            stateMachine.transition(review, ReviewStatus.FOLDED, ReviewActorType.ADMIN, adminId,
                    "FOLD", "ADMIN_REVIEW", reasonCode, reviewDetail);
            target = ReviewStatus.FOLDED;
        } else {
            boolean noAppeal = resolveNoAppeal(review, reasonCode);
            applyNoAppealFlag(review, noAppeal);
            reviewRepository.saveAndFlush(review);
            ReviewRejectReasonCode rejectCode = ReviewRejectReasonCode.valueOf(reasonCode);
            reviewDetail = json(reviewAuditDetail(review, reasonCode, note,
                    rejectCode.message(), noAppeal));
            stateMachine.transition(review, ReviewStatus.REJECTED, ReviewActorType.ADMIN, adminId,
                    "REJECT", "ADMIN_REVIEW", reasonCode, reviewDetail);
            target = ReviewStatus.REJECTED;
        }

        Map<String, Object> adminDetail = new LinkedHashMap<>();
        adminDetail.put("action", action.name());
        adminDetail.put("fromStatus", ReviewStatus.PENDING_MANUAL.name());
        adminDetail.put("toStatus", target.name());
        adminDetail.put("reasonCode", reasonCode);
        adminDetail.put("note", note);
        adminAuditService.record(adminId, adminRole, "REVIEW_" + action.name(), "REVIEW",
                reviewId.toString(), reasonCode, json(adminDetail), reviewId.toString(), ip,
                beforeHash, snapshotHash(review));

        // 申诉后的人工复审结果需回告作者（R16 申诉结果）；首次审核不发送。
        if (action != ReviewModerationAction.FOLD
                && auditRepository.existsByReviewIdAndAction(reviewId, "APPEAL")) {
            notificationService.send(review.getUserId(), NotificationType.APPEAL_RESULT,
                    "点评申诉结果", appealMessage(target), NotificationChannel.BOTH,
                    "REVIEW", reviewId.toString());
        }
        return target;
    }

    private String appealMessage(ReviewStatus target) {
        return target == ReviewStatus.PUBLISHED
                ? "你的申诉已通过，评价已恢复展示。"
                : "你的申诉未通过，评价维持驳回状态。";
    }

    private Map<String, Object> reviewAuditDetail(Review review, String reasonCode, String note,
                                                   String message, boolean noAppeal) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reasonCode", reasonCode);
        detail.put("note", note);
        if (message != null) {
            detail.put("message", message);
        }
        if (review.getAiRulesVersion() != null) {
            detail.put("ruleVersion", review.getAiRulesVersion());
        }
        detail.put("noAppeal", noAppeal);
        return detail;
    }

    private boolean resolveNoAppeal(Review review, String reasonCode) {
        String code = reasonCode == null ? "" : reasonCode.trim().toUpperCase();
        if ("PRIVACY".equals(code) || "ADVERTISING".equals(code) || "AD".equals(code)) {
            return true;
        }
        JsonNode flags = review.getAiFlags();
        if (flags != null) {
            if (flags.path("noAppeal").asBoolean(false)) {
                return true;
            }
            JsonNode flagArray = flags.path("flags");
            if (flagArray.isArray()) {
                for (JsonNode flag : flagArray) {
                    String value = flag.asText();
                    if ("PRIVACY_REDLINE".equals(value) || "AD_CONTACT".equals(value)) {
                        return true;
                    }
                }
            }
        }
        String content = review.getContent() == null ? "" : review.getContent();
        return content.contains("删帖") || content.contains("删评论") || content.contains("歧视");
    }

    private void applyNoAppealFlag(Review review, boolean noAppeal) {
        JsonNode current = review.getAiFlags();
        ObjectNode node = current instanceof ObjectNode object ? object.deepCopy()
                : objectMapper.createObjectNode();
        node.put("noAppeal", noAppeal);
        review.setAiFlags(node);
    }

    // ------------------------------------------------------------------
    // 队列视图
    // ------------------------------------------------------------------

    private ReviewQueueItem toQueueItem(Review review, QueueMeta meta) {
        List<ReviewDimension> dimensions = dimensionRepository
                .findByReviewIdOrderByDimensionCode(review.getId());
        boolean hasEvidence = evidenceRepository
                .existsByReviewIdAndMaterialStatus(review.getId(), "ACTIVE");
        boolean noAppeal = noAppealOf(review);
        return new ReviewQueueItem(
                review.getId(),
                review.getUscc(),
                companyName(review.getUscc()),
                aliasOf(review.getIdentityId()),
                review.getTotalScore(),
                dimensions.stream().map(d -> new ReviewItemView.DimensionView(
                        d.getDimensionCode().name(), d.getScore())).toList(),
                review.getContent(),
                hasEvidence,
                flagsOf(review),
                review.getAiConfidence(),
                review.getAiRulesVersion(),
                meta.source(),
                meta.enteredQueueAt(),
                meta.waitingHours(),
                meta.overdue(),
                noAppeal,
                canAppeal(review, noAppeal, meta.appealExists()));
    }

    private ReviewQueueDetail toDetail(Review review) {
        QueueMeta meta = metaOf(review);
        boolean noAppeal = noAppealOf(review);
        List<ReviewAuditView> audits = auditRepository
                .findByReviewIdOrderByCreatedAtAsc(review.getId()).stream()
                .map(audit -> new ReviewAuditView(
                        audit.getStage(),
                        audit.getAction(),
                        audit.getFromStatus() == null ? null : audit.getFromStatus().name(),
                        audit.getToStatus() == null ? null : audit.getToStatus().name(),
                        audit.getActorType() == null ? null : audit.getActorType().name(),
                        audit.getActorId(),
                        audit.getReasonCode(),
                        audit.getDetail(),
                        audit.getCreatedAt()))
                .toList();
        List<ReviewEvidenceMaterialView> materials = evidenceRepository
                .findByReviewId(review.getId()).stream()
                .map(e -> new ReviewEvidenceMaterialView(e.getId(), e.getType().name(),
                        e.getMaterialStatus(),
                        "/api/admin/materials/REVIEW_EVIDENCE/" + e.getId()
                                + "/url?ticketId=" + review.getId()))
                .toList();
        ReviewQueueItem item = toQueueItem(review, meta);
        return new ReviewQueueDetail(
                item.reviewId(), item.uscc(), item.companyName(), item.identityAlias(),
                item.totalScore(), item.dimensions(), item.content(), item.hasEvidence(),
                item.aiFlags(), item.aiConfidence(), item.rulesVersion(),
                review.getStatus(), item.source(), item.enteredQueueAt(), item.waitingHours(),
                item.overdue(), item.noAppeal(), item.canAppeal(), audits, materials);
    }

    private QueueMeta metaOf(Review review) {
        List<ReviewAudit> audits = auditRepository
                .findByReviewIdOrderByCreatedAtDesc(review.getId());
        Instant entered = null;
        boolean appealExists = false;
        boolean reported = false;
        for (ReviewAudit audit : audits) {
            if (audit.getAction() != null) {
                if (audit.getAction().startsWith("REPORT")) {
                    reported = true;
                }
                if ("APPEAL".equals(audit.getAction())) {
                    appealExists = true;
                }
            }
            // 审计按时间倒序，第一条 PENDING_MANUAL 即为最近一次进入待人工的时间。
            if (entered == null && audit.getToStatus() == ReviewStatus.PENDING_MANUAL
                    && audit.getCreatedAt() != null) {
                entered = audit.getCreatedAt();
            }
        }
        if (entered == null) {
            entered = review.getUpdatedAt() == null ? review.getCreatedAt() : review.getUpdatedAt();
        }
        boolean overdue = false;
        long waitingHours = 0;
        if (entered != null) {
            long minutes = Math.max(0, Duration.between(entered, Instant.now()).toMinutes());
            waitingHours = minutes / 60;
            overdue = waitingHours >= OVERDUE_HOURS;
        }

        JsonNode flags = review.getAiFlags();
        boolean specificClaim = flags != null && flags.path("specificAllegation").asBoolean(false);
        boolean copyDetected = flags != null && flags.path("copyDetected").asBoolean(false);
        boolean riskFlagged = flags != null && flags.path("riskSignals").isArray()
                && flags.path("riskSignals").size() > 0;
        List<String> flagList = flagsOf(review);
        if (!specificClaim && flagList.contains("SPECIFIC_ALLEGATION")) {
            specificClaim = true;
        }

        String source;
        if (appealExists) {
            source = SOURCE_APPEAL;
        } else if (reported) {
            source = SOURCE_REPORTED;
        } else if (specificClaim) {
            source = SOURCE_SPECIFIC_CLAIM;
        } else if (copyDetected) {
            source = SOURCE_COPY_DETECTED;
        } else if (riskFlagged) {
            source = SOURCE_RISK_FLAGGED;
        } else {
            source = SOURCE_AI_LOW_CONFIDENCE;
        }
        return new QueueMeta(source, entered, waitingHours, overdue, appealExists);
    }

    private boolean canAppeal(Review review, boolean noAppeal, boolean appealExists) {
        return review.getStatus() == ReviewStatus.REJECTED && !noAppeal && !appealExists;
    }

    private boolean noAppealOf(Review review) {
        JsonNode flags = review.getAiFlags();
        return flags != null && flags.path("noAppeal").asBoolean(false);
    }

    private List<String> flagsOf(Review review) {
        List<String> flags = new ArrayList<>();
        JsonNode flagArray = review.getAiFlags() == null ? null : review.getAiFlags().path("flags");
        if (flagArray != null && flagArray.isArray()) {
            flagArray.forEach(node -> flags.add(node.asText()));
        }
        return flags;
    }

    private boolean matchesKeyword(ReviewQueueItem item, String keyword) {
        return contains(item.uscc(), keyword)
                || contains(item.companyName(), keyword)
                || contains(item.content(), keyword)
                || contains(item.identityAlias(), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private String companyName(String uscc) {
        return companyRepository.findById(uscc).map(Company::getName).orElse(null);
    }

    private String aliasOf(UUID identityId) {
        return identityRepository.findById(identityId).map(AnonymousIdentity::getAlias).orElse(null);
    }

    // ------------------------------------------------------------------
    // 校验与工具
    // ------------------------------------------------------------------

    private String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String source = raw.trim().toUpperCase();
        return switch (source) {
            case "AI_LOW_CONFIDENCE", "LOW_CONFIDENCE" -> SOURCE_AI_LOW_CONFIDENCE;
            case "SPECIFIC_CLAIM", "SPECIFIC_ALLEGATION", "ALLEGED" -> SOURCE_SPECIFIC_CLAIM;
            case "REPORTED" -> SOURCE_REPORTED;
            case "COPY_DETECTED", "COPY" -> SOURCE_COPY_DETECTED;
            case "RISK_FLAGGED", "RISK" -> SOURCE_RISK_FLAGGED;
            case "APPEAL" -> SOURCE_APPEAL;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "source 不正确");
        };
    }

    private ReviewModerationAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "action 不能为空");
        }
        String action = raw.trim().toUpperCase();
        return switch (action) {
            case "APPROVE", "APPROVED", "PUBLISH" -> ReviewModerationAction.APPROVE;
            case "FOLD", "FOLDED" -> ReviewModerationAction.FOLD;
            case "REJECT", "REJECTED" -> ReviewModerationAction.REJECT;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "action 不正确");
        };
    }

    private String requireReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写 reasonCode");
        }
        String code = reasonCode.trim();
        if (code.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reasonCode 过长");
        }
        return code;
    }

    private ReviewRejectReasonCode requireRejectReason(String reasonCode) {
        String code = requireReason(reasonCode);
        try {
            return ReviewRejectReasonCode.from(code);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "驳回原因码不正确");
        }
    }

    private String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写 note");
        }
        String message = note.trim();
        if (message.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "note 不能超过 " + MAX_NOTE_LENGTH + " 字");
        }
        return message;
    }

    private String snapshotHash(Review review) {
        return sha256(review.getStatus() + "|" + review.getTotalScore() + "|" + review.getContent());
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
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

    private static String joinIds(List<UUID> ids) {
        StringBuilder builder = new StringBuilder();
        for (UUID id : ids) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private record QueueMeta(String source, Instant enteredQueueAt, long waitingHours,
                             boolean overdue, boolean appealExists) {
    }
}
