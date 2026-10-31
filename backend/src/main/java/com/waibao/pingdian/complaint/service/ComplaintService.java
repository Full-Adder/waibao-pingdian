package com.waibao.pingdian.complaint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.complaint.config.ComplaintProperties;
import com.waibao.pingdian.complaint.domain.Complaint;
import com.waibao.pingdian.complaint.domain.ComplaintEvidence;
import com.waibao.pingdian.complaint.domain.ComplaintParty;
import com.waibao.pingdian.complaint.domain.ComplaintStatus;
import com.waibao.pingdian.complaint.domain.EvidenceGrade;
import com.waibao.pingdian.complaint.repo.ComplaintEvidenceRepository;
import com.waibao.pingdian.complaint.repo.ComplaintRepository;
import com.waibao.pingdian.complaint.web.dto.ComplaintCreatedResult;
import com.waibao.pingdian.complaint.web.dto.ComplaintDetailView;
import com.waibao.pingdian.complaint.web.dto.ComplaintEvidenceView;
import com.waibao.pingdian.complaint.web.dto.ComplaintMaterialView;
import com.waibao.pingdian.complaint.web.dto.MyComplaintView;
import com.waibao.pingdian.notification.domain.NotificationChannel;
import com.waibao.pingdian.notification.domain.NotificationType;
import com.waibao.pingdian.notification.service.NotificationService;
import com.waibao.pingdian.provider.ObjectStorageProvider;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewAudit;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewAuditRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.review.service.ReviewService;
import com.waibao.pingdian.review.service.ReviewStateMachine;
import com.waibao.pingdian.security.CryptoService;
import com.waibao.pingdian.security.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 企业投诉主链路：公开申诉、主体核验、双向举证与作者侧工单。
 * 关键约束：未核验不建单不改状态（AC-09）；同一评价仅一次投诉（AC-12）。
 */
@Service
public class ComplaintService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NOTE = 500;
    private static final String ANOMALY_ACTION = "COMPLAINT_DUPLICATE_ANOMALY";

    private final CurrentUserService currentUserService;
    private final ComplaintRepository complaintRepository;
    private final ComplaintEvidenceRepository evidenceRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewAuditRepository reviewAuditRepository;
    private final ReviewStateMachine stateMachine;
    private final ReviewService reviewService;
    private final EnterpriseResponseService responseService;
    private final ComplaintBlocklistService blocklistService;
    private final ObjectStorageProvider storageProvider;
    private final CryptoService cryptoService;
    private final NotificationService notificationService;
    private final AdminAuditService auditService;
    private final ComplaintProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTemplate;

    public ComplaintService(CurrentUserService currentUserService,
                            ComplaintRepository complaintRepository,
                            ComplaintEvidenceRepository evidenceRepository,
                            ReviewRepository reviewRepository,
                            ReviewAuditRepository reviewAuditRepository,
                            ReviewStateMachine stateMachine,
                            ReviewService reviewService,
                            EnterpriseResponseService responseService,
                            ComplaintBlocklistService blocklistService,
                            ObjectStorageProvider storageProvider,
                            CryptoService cryptoService,
                            NotificationService notificationService,
                            AdminAuditService auditService,
                            ComplaintProperties properties,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.currentUserService = currentUserService;
        this.complaintRepository = complaintRepository;
        this.evidenceRepository = evidenceRepository;
        this.reviewRepository = reviewRepository;
        this.reviewAuditRepository = reviewAuditRepository;
        this.stateMachine = stateMachine;
        this.reviewService = reviewService;
        this.responseService = responseService;
        this.blocklistService = blocklistService;
        this.storageProvider = storageProvider;
        this.cryptoService = cryptoService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------
    // 公开企业申诉
    // ------------------------------------------------------------------

    @Transactional
    public ComplaintCreatedResult submitAppeal(String rawUscc, UUID reviewId, String contact,
                                               MultipartFile licenseFile, MultipartFile letterFile,
                                               boolean amountFlag, boolean safetyFlag, String ip) {
        String uscc = ComplaintBlocklistService.normalizeUscc(rawUscc);
        if (uscc == null || !uscc.matches("^[0-9A-Z]{18}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "USCC 格式不正确（需 18 位）");
        }
        if (reviewId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须指定目标点评 reviewId");
        }
        if (contact == null || !contact.trim().matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "企业联系人手机号格式不正确");
        }
        if (licenseFile == null || licenseFile.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传营业执照 licenseFile");
        }
        if (letterFile == null || letterFile.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传加盖公章的申诉函 letterFile");
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
        // 仅已发布点评可被首次投诉；未生成工单前绝不改点评状态（AC-09）。
        if (review.getStatus() != ReviewStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅已发布点评可发起投诉");
        }
        if (!uscc.equals(ComplaintBlocklistService.normalizeUscc(review.getUscc()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提交的 USCC 与评价所属公司不一致");
        }
        if (blocklistService.isBlocked(uscc)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该主体已被限制提交投诉");
        }
        if (complaintRepository.existsByReviewId(reviewId)) {
            recordDuplicateAnomaly(reviewId, uscc);
            throw new BusinessException(ErrorCode.CONFLICT, "该评价已有投诉记录");
        }

        String licenseKey = storeFile(licenseFile, "COMPLAINT_LICENSE", reviewId.toString());
        String letterKey = storeFile(letterFile, "COMPLAINT_LETTER", reviewId.toString());

        Complaint complaint = new Complaint();
        complaint.setReviewId(reviewId);
        complaint.setClaimantUscc(uscc);
        complaint.setClaimantContactCipher(cryptoService.encrypt(contact.trim()));
        complaint.setLicenseFileKey(licenseKey);
        complaint.setLetterFileKey(letterKey);
        complaint.setSubjectVerified(false);
        complaint.setType("CONTENT_DISPUTE");
        complaint.setAmountFlag(amountFlag);
        complaint.setSafetyFlag(safetyFlag);
        complaint.setStatus(ComplaintStatus.PENDING_VERIFY);
        complaintRepository.save(complaint);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("complaintId", complaint.getId().toString());
        detail.put("uscc", uscc);
        detail.put("ip", ip);
        recordSystemAudit(reviewId, "COMPLAINT_SUBMIT", json(detail));
        return new ComplaintCreatedResult(complaint.getId(), complaint.getStatus());
    }

    // ------------------------------------------------------------------
    // 管理端列表 / 详情
    // ------------------------------------------------------------------

    public PageResult<ComplaintDetailView> list(String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        ComplaintStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = ComplaintStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "投诉状态不正确");
            }
        }
        Page<Complaint> result = filter == null
                ? complaintRepository.findAll(pageable)
                : complaintRepository.findByStatus(filter, pageable);
        List<ComplaintDetailView> items = result.getContent().stream().map(this::toDetail).toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    public ComplaintDetailView detail(UUID complaintId) {
        return toDetail(requireComplaint(complaintId));
    }

    // ------------------------------------------------------------------
    // 主体核验
    // ------------------------------------------------------------------

    @Transactional
    public void verify(UUID complaintId, boolean verified, String note,
                       UUID adminId, AdminRole adminRole, String ip) {
        Complaint complaint = requireComplaint(complaintId);
        if (complaint.getStatus() != ComplaintStatus.PENDING_VERIFY) {
            throw new BusinessException(ErrorCode.CONFLICT, "该投诉已完成主体核验");
        }
        String safeNote = note == null ? null : note.trim();
        if (safeNote != null && safeNote.length() > MAX_NOTE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "备注不能超过 " + MAX_NOTE + " 字");
        }

        if (!verified) {
            complaint.setStatus(ComplaintStatus.REJECTED);
            complaintRepository.save(complaint);
            auditService.record(adminId, adminRole, "COMPLAINT_VERIFY_REJECT", "COMPLAINT",
                    complaintId.toString(), "SUBJECT_MISMATCH", safeNote, complaintId.toString(), ip);
            return;
        }

        Review review = reviewRepository.findById(complaint.getReviewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
        if (review.getStatus() != ReviewStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "点评当前状态不可转为争议中");
        }

        Instant deadline = Instant.now().plus(properties.getDeadlineHours(), java.time.temporal.ChronoUnit.HOURS);
        complaint.setSubjectVerified(true);
        complaint.setStatus(ComplaintStatus.EVIDENCE_COLLECTING);
        complaint.setDeadline(deadline);
        complaintRepository.save(complaint);

        review.setEverDisputed(true);
        review.setDisputeDeadline(deadline);
        reviewRepository.saveAndFlush(review);
        Map<String, Object> transitionDetail = new LinkedHashMap<>();
        transitionDetail.put("complaintId", complaintId.toString());
        transitionDetail.put("deadline", deadline.toString());
        stateMachine.transition(review, ReviewStatus.DISPUTED, ReviewActorType.ADMIN, adminId,
                "COMPLAINT_VERIFIED", "COMPLAINT_VERIFY", "SUBJECT_VERIFIED", json(transitionDetail));

        auditService.record(adminId, adminRole, "COMPLAINT_VERIFY_APPROVE", "COMPLAINT",
                complaintId.toString(), "SUBJECT_VERIFIED", json(transitionDetail), complaintId.toString(), ip);

        notificationService.send(review.getUserId(), NotificationType.DISPUTE_STARTED,
                "评价进入争议举证",
                "你的评价已被企业投诉且主体核验通过，现已进入 " + properties.getDeadlineHours()
                        + " 小时双向举证阶段，截止时间 " + formatDeadline(deadline) + "。",
                NotificationChannel.BOTH, "COMPLAINT", complaintId.toString());
        notifyEnterprise(complaint, "DISPUTE_STARTED", Map.of(
                "complaintId", complaintId.toString(), "deadline", deadline.toString()));
    }

    // ------------------------------------------------------------------
    // 双向举证
    // ------------------------------------------------------------------

    @Transactional
    public ComplaintEvidenceView addAdminEvidence(UUID complaintId, String party, String grade,
                                                  String note, MultipartFile file,
                                                  UUID adminId, AdminRole adminRole, String ip) {
        Complaint complaint = requireComplaint(complaintId);
        assertEvidenceWindow(complaint);
        ComplaintParty side = parseParty(party);
        EvidenceGrade evidenceGrade = parseGrade(grade);
        String safeNote = validateNote(note);

        Uploaded uploaded = upload(file, "COMPLAINT_EVIDENCE", complaintId.toString());
        ComplaintEvidence evidence = new ComplaintEvidence();
        evidence.setComplaintId(complaintId);
        evidence.setSide(side);
        evidence.setFileKey(uploaded.fileKey());
        evidence.setFileHash(uploaded.hash());
        evidence.setFileName(uploaded.fileName());
        evidence.setEvidenceLevel(evidenceGrade.persisted());
        evidence.setNote(safeNote);
        evidenceRepository.save(evidence);

        if (side == ComplaintParty.ENTERPRISE) {
            if (safeNote != null) {
                complaint.setEnterpriseEvidenceNote(safeNote);
            }
            if (grade != null && !grade.isBlank()) {
                complaint.setEnterpriseGrade(evidenceGrade.persisted());
            }
        } else {
            if (safeNote != null) {
                complaint.setUserEvidenceNote(safeNote);
            }
            if (grade != null && !grade.isBlank()) {
                complaint.setUserGrade(evidenceGrade.persisted());
            }
        }
        complaintRepository.save(complaint);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("party", side.name());
        detail.put("grade", evidenceGrade.name());
        detail.put("evidenceId", evidence.getId().toString());
        detail.put("note", safeNote);
        auditService.record(adminId, adminRole, "COMPLAINT_EVIDENCE", "COMPLAINT",
                complaintId.toString(), null, json(detail), complaintId.toString(), ip);
        return toEvidenceView(evidence);
    }

    @Transactional
    public ComplaintEvidenceView addUserEvidence(UUID complaintId, String grade, String note,
                                                 MultipartFile file) {
        UserAccount user = currentUserService.getUser();
        if (user.getRealnameStatus() != RealnameStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先完成实名认证后再提交举证");
        }
        Complaint complaint = requireComplaint(complaintId);
        Review review = reviewRepository.findById(complaint.getReviewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
        if (!review.getUserId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "投诉工单不存在");
        }
        assertEvidenceWindow(complaint);
        EvidenceGrade evidenceGrade = parseGrade(grade);
        String safeNote = validateNote(note);

        Uploaded uploaded = upload(file, "COMPLAINT_EVIDENCE", complaintId.toString());
        ComplaintEvidence evidence = new ComplaintEvidence();
        evidence.setComplaintId(complaintId);
        evidence.setSide(ComplaintParty.USER);
        evidence.setFileKey(uploaded.fileKey());
        evidence.setFileHash(uploaded.hash());
        evidence.setFileName(uploaded.fileName());
        evidence.setEvidenceLevel(evidenceGrade.persisted());
        evidence.setNote(safeNote);
        evidenceRepository.save(evidence);

        if (safeNote != null) {
            complaint.setUserEvidenceNote(safeNote);
        }
        if (grade != null && !grade.isBlank()) {
            complaint.setUserGrade(evidenceGrade.persisted());
        }
        complaintRepository.save(complaint);
        recordUserAudit(complaint.getReviewId(), user.getId(), "COMPLAINT_EVIDENCE", null,
                "用户提交举证材料");
        return toEvidenceView(evidence);
    }

    /**
     * 作者查看自己的投诉工单与倒计时、举证状态、企业回应。
     */
    public MyComplaintView mine(UUID reviewId) {
        UserAccount user = currentUserService.getUser();
        Review review = reviewRepository.findById(reviewId)
                .filter(r -> r.getUserId().equals(user.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
        Complaint complaint = complaintRepository.findByReviewId(review.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该评价暂无投诉工单"));

        Instant now = Instant.now();
        long remaining = complaint.getDeadline() == null ? 0
                : Math.max(0, Duration.between(now, complaint.getDeadline()).getSeconds());
        boolean userSubmitted = evidenceRepository.existsByComplaintIdAndSide(
                complaint.getId(), ComplaintParty.USER);
        boolean enterpriseSubmitted = evidenceRepository.existsByComplaintIdAndSide(
                complaint.getId(), ComplaintParty.ENTERPRISE);
        List<com.waibao.pingdian.complaint.web.dto.EnterpriseResponseView> responses =
                responseService.approvedOf(review.getId());
        return new MyComplaintView(
                complaint.getId(), review.getId(), complaint.getStatus(), complaint.isSubjectVerified(),
                complaint.getDeadline(), remaining, isOverdue(complaint), userSubmitted, enterpriseSubmitted,
                complaint.getUserEvidenceNote(), complaint.getEnterpriseEvidenceNote(),
                complaint.getResolution(), !responses.isEmpty(), responses);
    }

    // ------------------------------------------------------------------
    // 内部：视图与校验
    // ------------------------------------------------------------------

    private ComplaintDetailView toDetail(Complaint complaint) {
        Review review = reviewRepository.findById(complaint.getReviewId()).orElse(null);
        List<ComplaintEvidenceView> evidences = evidenceRepository
                .findByComplaintIdOrderByCreatedAtAsc(complaint.getId()).stream()
                .filter(e -> "ACTIVE".equals(e.getMaterialStatus()))
                .map(this::toEvidenceView)
                .toList();
        List<ComplaintMaterialView> materials = materialsOf(complaint, evidences);
        long remaining = complaint.getDeadline() == null ? 0
                : Math.max(0, Duration.between(Instant.now(), complaint.getDeadline()).getSeconds());
        return new ComplaintDetailView(
                complaint.getId(), complaint.getReviewId(),
                review == null ? null : reviewService.toView(review),
                complaint.getClaimantUscc(), complaint.isSubjectVerified(), complaint.getType(),
                complaint.isAmountFlag(), complaint.isSafetyFlag(), complaint.getStatus(),
                complaint.getDeadline(), remaining, isOverdue(complaint),
                complaint.getEnterpriseEvidenceNote(), complaint.getUserEvidenceNote(),
                complaint.getResolution(), complaint.getCreatedAt(), complaint.isDualReviewRequired(),
                complaint.getEnterpriseGrade(), complaint.getUserGrade(), complaint.getRulingReason(),
                complaint.getCitedMaterials(), complaint.getRuleClause(), complaint.getProposedResolution(),
                complaint.getProposedBy(), complaint.getProposedAt(), evidences, materials,
                responseService.approvedOf(complaint.getReviewId()));
    }

    private List<ComplaintMaterialView> materialsOf(Complaint complaint,
                                                    List<ComplaintEvidenceView> evidences) {
        List<ComplaintMaterialView> materials = new ArrayList<>();
        UUID id = complaint.getId();
        if (complaint.getLicenseFileKey() != null) {
            materials.add(new ComplaintMaterialView("COMPLAINT_LICENSE", id.toString(),
                    "/api/admin/materials/COMPLAINT_LICENSE/" + id + "/url?ticketId=" + id));
        }
        if (complaint.getLetterFileKey() != null) {
            materials.add(new ComplaintMaterialView("COMPLAINT_LETTER", id.toString(),
                    "/api/admin/materials/COMPLAINT_LETTER/" + id + "/url?ticketId=" + id));
        }
        for (ComplaintEvidenceView evidence : evidences) {
            materials.add(new ComplaintMaterialView("COMPLAINT_EVIDENCE", evidence.id().toString(),
                    "/api/admin/materials/COMPLAINT_EVIDENCE/" + evidence.id()
                            + "/url?ticketId=" + id));
        }
        return materials;
    }

    private ComplaintEvidenceView toEvidenceView(ComplaintEvidence evidence) {
        return new ComplaintEvidenceView(evidence.getId(), evidence.getSide().name(),
                evidence.getEvidenceLevel(), evidence.getNote(), evidence.getFileName(),
                evidence.getMaterialStatus(), evidence.getCreatedAt());
    }

    private Complaint requireComplaint(UUID complaintId) {
        return complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投诉工单不存在"));
    }

    private void assertEvidenceWindow(Complaint complaint) {
        if (complaint.getStatus() != ComplaintStatus.EVIDENCE_COLLECTING) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态不可提交举证");
        }
        if (complaint.getDeadline() != null && Instant.now().isAfter(complaint.getDeadline())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "举证已截止，超时视为弃权");
        }
    }

    private ComplaintParty parseParty(String party) {
        if (party == null || party.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须指定举证方 party");
        }
        try {
            return ComplaintParty.valueOf(party.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "举证方必须为 ENTERPRISE 或 USER");
        }
    }

    private EvidenceGrade parseGrade(String grade) {
        try {
            return EvidenceGrade.from(grade);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "证据等级必须为 A/B/C/NONE");
        }
    }

    private String validateNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String value = note.trim();
        if (value.length() > MAX_NOTE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "备注不能超过 " + MAX_NOTE + " 字");
        }
        return value;
    }

    private Uploaded upload(MultipartFile file, String ownerType, String ownerId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传举证材料 file");
        }
        try {
            byte[] bytes = file.getBytes();
            String contentType = file.getContentType() == null
                    ? "application/octet-stream" : file.getContentType();
            String fileKey = storageProvider.put(bytes, contentType, ownerType, ownerId);
            String fileName = sanitizeFileName(file.getOriginalFilename());
            return new Uploaded(fileKey, sha256(bytes), fileName);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取上传文件失败");
        }
    }

    private String storeFile(MultipartFile file, String ownerType, String ownerId) {
        return upload(file, ownerType, ownerId).fileKey();
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "material";
        }
        String cleaned = name.replace('\\', '_').replace('/', '_').trim();
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }

    private boolean isOverdue(Complaint complaint) {
        return complaint.getDeadline() != null && Instant.now().isAfter(complaint.getDeadline());
    }

    private void recordDuplicateAnomaly(UUID reviewId, String uscc) {
        requiresNewTemplate.execute(status -> {
            ReviewAudit audit = new ReviewAudit();
            audit.setReviewId(reviewId);
            audit.setStage("COMPLAINT");
            audit.setActorType(ReviewActorType.SYSTEM);
            audit.setAction(ANOMALY_ACTION);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("uscc", uscc);
            detail.put("reason", "同一评价二次投诉");
            audit.setDetail(json(detail));
            reviewAuditRepository.save(audit);
            return null;
        });
    }

    private void recordSystemAudit(UUID reviewId, String action, String detailText) {
        ReviewAudit audit = new ReviewAudit();
        audit.setReviewId(reviewId);
        audit.setStage("COMPLAINT");
        audit.setActorType(ReviewActorType.SYSTEM);
        audit.setAction(action);
        audit.setDetail(detailText);
        reviewAuditRepository.save(audit);
    }

    private void recordUserAudit(UUID reviewId, UUID userId, String action, String reasonCode,
                                 String detailText) {
        ReviewAudit audit = new ReviewAudit();
        audit.setReviewId(reviewId);
        audit.setStage("COMPLAINT");
        audit.setActorId(userId);
        audit.setActorType(ReviewActorType.USER);
        audit.setAction(action);
        audit.setReasonCode(reasonCode);
        audit.setDetail(detailText);
        reviewAuditRepository.save(audit);
    }

    private void notifyEnterprise(Complaint complaint, String template, Map<String, String> params) {
        try {
            String phone = cryptoService.decrypt(complaint.getClaimantContactCipher());
            notificationService.sendSms(phone, template, params);
        } catch (RuntimeException ignored) {
            // 联系人手机号异常不应阻断工单流转。
        }
    }

    private String formatDeadline(Instant deadline) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(deadline);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("文件哈希计算失败", ex);
        }
    }

    private record Uploaded(String fileKey, String hash, String fileName) {
    }
}
