package com.waibao.pingdian.complaint.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.redis.RedisLockService;
import com.waibao.pingdian.complaint.config.ComplaintProperties;
import com.waibao.pingdian.complaint.domain.Complaint;
import com.waibao.pingdian.complaint.domain.ComplaintParty;
import com.waibao.pingdian.complaint.domain.ComplaintStatus;
import com.waibao.pingdian.complaint.repo.ComplaintEvidenceRepository;
import com.waibao.pingdian.complaint.repo.ComplaintRepository;
import com.waibao.pingdian.notification.domain.NotificationChannel;
import com.waibao.pingdian.notification.domain.NotificationType;
import com.waibao.pingdian.notification.service.NotificationService;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewAudit;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewAuditRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.review.service.ReviewStateMachine;
import com.waibao.pingdian.security.CryptoService;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 举证时限调度（XXL-Job 每 5 分钟触发）：
 * 1) 截止前 24h 对未举证方发送必达提醒（幂等）；
 * 2) 截止后双方均无证据自动裁定 INSUFFICIENT 并折叠，仅一方举证转 PENDING_RULING。
 * 入口使用 Redis 分布式锁（按 5 分钟时间片），防止多实例重复扫描。
 */
@Service
public class ComplaintDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger(ComplaintDeadlineJob.class);
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private static final String REMINDER_TEMPLATE = "EVIDENCE_REMINDER";
    private static final String ENTERPRISE_REMINDER_ACTION = "EVIDENCE_REMINDER_ENTERPRISE";
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final ComplaintRepository complaintRepository;
    private final ComplaintEvidenceRepository evidenceRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewAuditRepository reviewAuditRepository;
    private final ReviewStateMachine stateMachine;
    private final NotificationService notificationService;
    private final AdminAuditService auditService;
    private final ComplaintProperties properties;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final RedisLockService lockService;

    public ComplaintDeadlineJob(ComplaintRepository complaintRepository,
                                ComplaintEvidenceRepository evidenceRepository,
                                ReviewRepository reviewRepository,
                                ReviewAuditRepository reviewAuditRepository,
                                ReviewStateMachine stateMachine,
                                NotificationService notificationService,
                                AdminAuditService auditService,
                                ComplaintProperties properties,
                                CryptoService cryptoService,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                RedisLockService lockService) {
        this.complaintRepository = complaintRepository;
        this.evidenceRepository = evidenceRepository;
        this.reviewRepository = reviewRepository;
        this.reviewAuditRepository = reviewAuditRepository;
        this.stateMachine = stateMachine;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.properties = properties;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.lockService = lockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @XxlJob("complaintDeadlineJob")
    public void complaintDeadlineJob() {
        lockService.runWithLock(RedisLockService.fiveMinuteKey("complaint-deadline"), LOCK_TTL, () -> {
            sendEvidenceReminders();
            processDeadlines();
            return null;
        });
    }

    /**
     * 截止前 reminderHoursBefore 小时内，对未提交证据的一方发送提醒。
     * 同一用户 + complaintId + EVIDENCE_REMINDER 只写一条；企业联系人用 review_audit 做幂等标记。
     */
    public int sendEvidenceReminders() {
        int sent = 0;
        for (Complaint complaint : collectingComplaints()) {
            try {
                Boolean result = transactionTemplate.execute(status -> remindOne(complaint.getId()));
                if (Boolean.TRUE.equals(result)) {
                    sent++;
                }
            } catch (Exception ex) {
                log.error("evidence reminder failed complaintId={} type={}",
                        complaint.getId(), ex.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /**
     * 截止后：双方均无证据自动裁定并折叠；任一方有证据则转 PENDING_RULING 等人工裁定。
     */
    public int processDeadlines() {
        int handled = 0;
        for (Complaint complaint : collectingComplaints()) {
            try {
                Boolean result = transactionTemplate.execute(status -> processDeadlineOne(complaint.getId()));
                if (Boolean.TRUE.equals(result)) {
                    handled++;
                }
            } catch (Exception ex) {
                log.error("deadline scan failed complaintId={} type={}",
                        complaint.getId(), ex.getClass().getSimpleName());
            }
        }
        return handled;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private boolean remindOne(UUID complaintId) {
        Complaint complaint = complaintRepository.findById(complaintId).orElse(null);
        if (complaint == null || complaint.getStatus() != ComplaintStatus.EVIDENCE_COLLECTING
                || complaint.getDeadline() == null) {
            return false;
        }
        Instant now = Instant.now();
        Instant reminderAt = complaint.getDeadline()
                .minus(properties.getReminderHoursBefore(), ChronoUnit.HOURS);
        if (now.isBefore(reminderAt) || now.isAfter(complaint.getDeadline())) {
            return false;
        }
        Review review = reviewRepository.findById(complaint.getReviewId()).orElse(null);
        if (review == null) {
            return false;
        }
        boolean userMissing = !hasEvidence(complaintId, ComplaintParty.USER);
        boolean enterpriseMissing = !hasEvidence(complaintId, ComplaintParty.ENTERPRISE);
        boolean sent = false;

        if (userMissing) {
            String body = "企业投诉举证将于 " + DEADLINE_FORMAT.format(complaint.getDeadline())
                    + "（北京时间）截止，你尚未提交佐证材料。请尽快提交，超时未提交视为弃权。";
            if (notificationService.sendOnce(review.getUserId(), NotificationType.EVIDENCE_REMINDER,
                    "举证截止提醒", body, NotificationChannel.BOTH, "COMPLAINT",
                    complaintId.toString()) != null) {
                sent = true;
            }
        }
        if (enterpriseMissing
                && !reviewAuditRepository.existsByReviewIdAndAction(
                complaint.getReviewId(), ENTERPRISE_REMINDER_ACTION)) {
            if (remindEnterprise(complaint)) {
                recordEnterpriseReminder(complaint);
                sent = true;
            }
        }
        return sent;
    }

    private boolean processDeadlineOne(UUID complaintId) {
        Complaint complaint = complaintRepository.findById(complaintId).orElse(null);
        if (complaint == null || complaint.getStatus() != ComplaintStatus.EVIDENCE_COLLECTING
                || complaint.getDeadline() == null || Instant.now().isBefore(complaint.getDeadline())) {
            return false;
        }
        boolean userHasEvidence = hasEvidence(complaintId, ComplaintParty.USER);
        boolean enterpriseHasEvidence = hasEvidence(complaintId, ComplaintParty.ENTERPRISE);
        if (userHasEvidence || enterpriseHasEvidence) {
            complaint.setStatus(ComplaintStatus.PENDING_RULING);
            complaintRepository.save(complaint);
            return true;
        }

        Review review = reviewRepository.findById(complaint.getReviewId()).orElse(null);
        if (review == null || review.getStatus() != ReviewStatus.DISPUTED) {
            log.warn("skip timeout ruling, review not disputed complaintId={}", complaintId);
            return false;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("phase", "TIMEOUT");
        detail.put("actorType", "SYSTEM");
        detail.put("reviewId", review.getId().toString());
        detail.put("result", "INSUFFICIENT");
        detail.put("reason", "举证截止，双方均未提交证据");
        String detailJson = json(detail);

        complaint.setStatus(ComplaintStatus.RULED_INSUFFICIENT);
        complaint.setResolution("INSUFFICIENT");
        complaint.setRulingReason("举证截止，双方均未提交证据，按 PRD 8.5 判定矩阵自动判定。");
        complaint.setRuleClause("PRD 8.5 判定矩阵");
        complaint.setCitedMaterials("[]");
        complaintRepository.save(complaint);

        stateMachine.transition(review, ReviewStatus.FOLDED, ReviewActorType.SYSTEM, null,
                "TIMEOUT_RULING", "COMPLAINT_TIMEOUT", "INSUFFICIENT", detailJson);

        auditService.record(SYSTEM_ACTOR, AdminRole.SUPER_ADMIN, "TIMEOUT_RULING", "COMPLAINT",
                complaintId.toString(), "INSUFFICIENT", detailJson, complaintId.toString(), null);

        notificationService.send(review.getUserId(), NotificationType.RULING_RESULT, "投诉裁定结果",
                "争议未决，双方举证不足，评价已折叠。", NotificationChannel.BOTH,
                "COMPLAINT", complaintId.toString());
        remindEnterprise(complaint, "RULING_RESULT", Map.of(
                "complaintId", complaintId.toString(), "result", "INSUFFICIENT"));
        return true;
    }

    private boolean remindEnterprise(Complaint complaint) {
        return remindEnterprise(complaint, REMINDER_TEMPLATE, Map.of(
                "complaintId", complaint.getId().toString(), "deadline", complaint.getDeadline().toString()));
    }

    private boolean remindEnterprise(Complaint complaint, String template, Map<String, String> params) {
        try {
            String phone = cryptoService.decrypt(complaint.getClaimantContactCipher());
            return notificationService.sendSms(phone, template, params);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void recordEnterpriseReminder(Complaint complaint) {
        ReviewAudit audit = new ReviewAudit();
        audit.setReviewId(complaint.getReviewId());
        audit.setStage("COMPLAINT");
        audit.setActorType(ReviewActorType.SYSTEM);
        audit.setAction(ENTERPRISE_REMINDER_ACTION);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("complaintId", complaint.getId().toString());
        detail.put("deadline", complaint.getDeadline().toString());
        audit.setDetail(json(detail));
        reviewAuditRepository.save(audit);
    }

    private boolean hasEvidence(UUID complaintId, ComplaintParty side) {
        return evidenceRepository.existsByComplaintIdAndSideAndMaterialStatus(
                complaintId, side, "ACTIVE");
    }

    private java.util.List<Complaint> collectingComplaints() {
        return complaintRepository.findByStatusOrderByDeadlineAsc(ComplaintStatus.EVIDENCE_COLLECTING);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
