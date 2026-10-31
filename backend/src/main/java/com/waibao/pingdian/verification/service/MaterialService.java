package com.waibao.pingdian.verification.service;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.account.domain.RiskSubjectType;
import com.waibao.pingdian.account.service.RiskSignalService;
import com.waibao.pingdian.audit.repo.AdminAuditLogRepository;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.provider.ObjectStorageProvider;
import com.waibao.pingdian.security.CurrentAdminService;
import com.waibao.pingdian.verification.domain.UserVerification;
import com.waibao.pingdian.verification.repo.UserVerificationRepository;
import com.waibao.pingdian.verification.web.dto.MaterialUrlResult;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 材料签名 URL：仅内容管理员及以上，必须带工单号，读取必审计；单日 >20 份写风险信号。
 * 支持核验材料与投诉材料（营业执照 / 申诉函 / 双方举证）。
 */
@Service
public class MaterialService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SIGNED_URL_TTL_SECONDS = 300;
    private static final int DAILY_READ_THRESHOLD = 20;

    private final CurrentAdminService currentAdminService;
    private final UserVerificationRepository verificationRepository;
    private final ObjectStorageProvider storageProvider;
    private final AdminAuditService auditService;
    private final AdminAuditLogRepository auditLogRepository;
    private final RiskSignalService riskSignalService;
    private final JdbcTemplate jdbcTemplate;

    public MaterialService(CurrentAdminService currentAdminService,
                           UserVerificationRepository verificationRepository,
                           ObjectStorageProvider storageProvider,
                           AdminAuditService auditService,
                           AdminAuditLogRepository auditLogRepository,
                           RiskSignalService riskSignalService,
                           JdbcTemplate jdbcTemplate) {
        this.currentAdminService = currentAdminService;
        this.verificationRepository = verificationRepository;
        this.storageProvider = storageProvider;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.riskSignalService = riskSignalService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public MaterialUrlResult signedUrl(String type, String id, String ticketId, String ip) {
        AdminUser admin = currentAdminService.getAdmin();
        UUID entityId = parseId(id);
        String ticket = ticketId == null ? "" : ticketId.trim();

        ResolvedMaterial material = resolveMaterial(type, entityId, ticket);
        String url = storageProvider.signedUrl(material.fileKey(), SIGNED_URL_TTL_SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(SIGNED_URL_TTL_SECONDS);

        auditService.record(admin.getId(), admin.getRole(), "MATERIAL_READ", type,
                entityId.toString(), null,
                "生成材料签名 URL（ticketId=" + ticket + "）", ticket, ip);

        Instant dayStart = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant();
        long todayReads = auditLogRepository.countByActorIdAndActionAndCreatedAtGreaterThanEqual(
                admin.getId(), "MATERIAL_READ", dayStart);
        if (todayReads > DAILY_READ_THRESHOLD) {
            // risk_signal 的 subject_type 约束仅允许 USER/IP/DEVICE，管理员以 USER + adminId 记录。
            riskSignalService.record(RiskSubjectType.USER, admin.getId().toString(),
                    "MATERIAL_READ_THRESHOLD", 30, Duration.ofHours(24));
        }
        return new MaterialUrlResult(url, expiresAt);
    }

    private ResolvedMaterial resolveMaterial(String type, UUID entityId, String ticketId) {
        if ("VERIFICATION".equals(type)) {
            requireTicket(entityId, ticketId);
            UserVerification verification = verificationRepository.findById(entityId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "核验记录不存在"));
            String fileKey = verification.getMaskedFileKey() != null
                    ? verification.getMaskedFileKey()
                    : verification.getFileKey();
            return new ResolvedMaterial(requireFileKey(fileKey, "核验材料不存在或已删除"));
        }
        if ("REVIEW_EVIDENCE".equals(type)) {
            requireTicket(entityId, ticketId);
            String fileKey;
            try {
                fileKey = jdbcTemplate.queryForObject(
                        "SELECT file_key FROM review_evidence WHERE review_id = ? AND material_status = 'ACTIVE' "
                                + "ORDER BY id LIMIT 1",
                        String.class, entityId);
            } catch (EmptyResultDataAccessException ex) {
                fileKey = null;
            }
            return new ResolvedMaterial(requireFileKey(fileKey, "点评佐证材料不存在或已删除"));
        }
        if ("COMPLAINT_EVIDENCE".equals(type)) {
            Map<String, Object> evidence = queryComplaintEvidence(entityId);
            if (evidence != null) {
                UUID complaintId = (UUID) evidence.get("complaint_id");
                if (!complaintId.toString().equals(ticketId)) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "工单号与材料不匹配");
                }
                return new ResolvedMaterial(requireFileKey((String) evidence.get("file_key"),
                        "投诉举证材料不存在"));
            }
            // 兼容旧入口：id 为 complaintId 时返回营业执照。
            requireTicket(entityId, ticketId);
            return new ResolvedMaterial(requireFileKey(complaintFile(entityId, "license_file_key"),
                    "投诉材料不存在"));
        }
        if ("COMPLAINT".equals(type) || "COMPLAINT_LICENSE".equals(type)) {
            requireTicket(entityId, ticketId);
            return new ResolvedMaterial(requireFileKey(complaintFile(entityId, "license_file_key"),
                    "营业执照不存在"));
        }
        if ("COMPLAINT_LETTER".equals(type)) {
            requireTicket(entityId, ticketId);
            return new ResolvedMaterial(requireFileKey(complaintFile(entityId, "letter_file_key"),
                    "申诉函不存在"));
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "材料类型不正确");
    }

    private void requireTicket(UUID entityId, String ticketId) {
        if (ticketId == null || !entityId.toString().equals(ticketId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "工单号与材料不匹配");
        }
    }

    private String complaintFile(UUID complaintId, String column) {
        try {
            String fileKey = jdbcTemplate.queryForObject(
                    "SELECT " + column + " FROM complaint WHERE id = ?", String.class, complaintId);
            return fileKey;
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "投诉记录不存在");
        }
    }

    private Map<String, Object> queryComplaintEvidence(UUID evidenceId) {
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT complaint_id, file_key FROM complaint_evidence WHERE id = ?", evidenceId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private String requireFileKey(String fileKey, String message) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, message);
        }
        return fileKey;
    }

    private UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "材料 id 格式不正确");
        }
    }

    private record ResolvedMaterial(String fileKey) {
    }
}
