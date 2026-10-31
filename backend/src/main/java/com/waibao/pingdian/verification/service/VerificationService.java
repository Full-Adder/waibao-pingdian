package com.waibao.pingdian.verification.service;

import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.account.service.ContributionService;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.notification.service.NotificationService;
import com.waibao.pingdian.provider.ObjectStorageProvider;
import com.waibao.pingdian.security.CurrentUserService;
import com.waibao.pingdian.verification.domain.MaterialStatus;
import com.waibao.pingdian.verification.domain.ProofType;
import com.waibao.pingdian.verification.domain.RejectReasonCode;
import com.waibao.pingdian.verification.domain.UserVerification;
import com.waibao.pingdian.verification.domain.VerificationStatus;
import com.waibao.pingdian.verification.repo.UserVerificationRepository;
import com.waibao.pingdian.verification.web.dto.VerificationItem;
import com.waibao.pingdian.verification.web.dto.MaterialDeletionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 在职佐证提交、查询与审核。材料仅入私有存储：原件加密，图片另存降采样脱敏副本。
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MASKED_SCALE_PERCENT = 20;

    private final CurrentUserService currentUserService;
    private final UserAccountRepository userAccountRepository;
    private final UserVerificationRepository verificationRepository;
    private final VerificationBadgeService badgeService;
    private final ObjectStorageProvider storageProvider;
    private final ContributionService contributionService;
    private final NotificationService notificationService;
    private final AdminAuditService auditService;

    public VerificationService(CurrentUserService currentUserService,
                               UserAccountRepository userAccountRepository,
                               UserVerificationRepository verificationRepository,
                               VerificationBadgeService badgeService,
                               ObjectStorageProvider storageProvider,
                               ContributionService contributionService,
                               NotificationService notificationService,
                               AdminAuditService auditService) {
        this.currentUserService = currentUserService;
        this.userAccountRepository = userAccountRepository;
        this.verificationRepository = verificationRepository;
        this.badgeService = badgeService;
        this.storageProvider = storageProvider;
        this.contributionService = contributionService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    @Transactional
    public VerificationItem submit(ProofType proofType, String companyUscc,
                                   String periodStart, String periodEnd, MultipartFile file) {
        UserAccount user = currentUserService.getUser();
        if (user.getRealnameStatus() != RealnameStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先完成实名认证后再提交在职佐证");
        }
        if (proofType == null || companyUscc == null || !companyUscc.trim().matches("^[0-9A-Z]{18}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "公司统一社会信用代码格式不正确");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传佐证材料");
        }

        LocalDate start = parseMonthStart(periodStart);
        LocalDate end = parseMonthEnd(periodEnd);
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "在职结束月不能早于起始月");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取上传文件失败");
        }
        String contentType = resolveContentType(file);
        String fileKey = storageProvider.put(bytes, contentType, "VERIFICATION", user.getId().toString());

        UserVerification verification = new UserVerification();
        verification.setUserId(user.getId());
        verification.setProofType(proofType);
        verification.setFileKey(fileKey);
        verification.setFileHash(sha256Hex(bytes));
        verification.setCompanyUscc(companyUscc.trim());
        verification.setPeriodStart(start);
        verification.setPeriodEnd(end);
        verification.setStatus(VerificationStatus.PENDING);

        byte[] masked = downsample(bytes, contentType);
        if (masked != null) {
            String maskedType = contentType.contains("png") ? "image/png" : "image/jpeg";
            verification.setMaskedFileKey(storageProvider.put(
                    masked, maskedType, "VERIFICATION_MASKED", user.getId().toString()));
        }
        verificationRepository.save(verification);
        return toItem(verification);
    }

    /**
     * 用户提前删除佐证材料：仅本人可操作，只删原件与脱敏副本；
     * 核验状态、时间段、类型保留，已发布评价不受影响。重复调用幂等返回当前状态。
     */
    @Transactional
    public MaterialDeletionResult deleteMaterial(UUID verificationId) {
        UUID userId = currentUserService.getUserId();
        UserVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "佐证记录不存在"));
        if (!verification.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除他人的佐证材料");
        }
        if (verification.getMaterialStatus() == MaterialStatus.DELETED) {
            return new MaterialDeletionResult(verificationId, verification.getMaterialStatus(),
                    verification.getMaterialDeletedAt());
        }

        String fileKey = verification.getFileKey();
        String maskedFileKey = verification.getMaskedFileKey();
        if (fileKey != null && !fileKey.isBlank()) {
            storageProvider.delete(fileKey);
        }
        if (maskedFileKey != null && !maskedFileKey.isBlank()) {
            storageProvider.delete(maskedFileKey);
        }

        Instant now = Instant.now();
        verification.setMaterialStatus(MaterialStatus.DELETED);
        verification.setMaterialDeletedAt(now);
        verification.setFileKey(null);
        verification.setMaskedFileKey(null);
        verificationRepository.save(verification);

        // 用户动作不属于后台审计域，admin_audit_log.actor_role 仅允许管理员角色；
        // 此处以结构化日志留痕（谁、何时、哪条佐证），不写管理员审计表。
        log.info("user_material_delete userId={} verificationId={} at={}", userId, verificationId, now);
        return new MaterialDeletionResult(verificationId, MaterialStatus.DELETED, now);
    }

    public PageResult<VerificationItem> listMine(int page, int size) {
        UUID userId = currentUserService.getUserId();
        Pageable pageable = pageable(page, size);
        Page<UserVerification> result = verificationRepository.findByUserId(userId, pageable);
        List<VerificationItem> items = result.getContent().stream().map(this::toItem).toList();
        return PageResult.of(items, page, size, result.getTotalElements());
    }

    public PageResult<VerificationItem> listForAdmin(VerificationStatus status, int page, int size) {
        Pageable pageable = pageable(page, size);
        Page<UserVerification> result = status == null
                ? verificationRepository.findAll(pageable)
                : verificationRepository.findByStatus(status, pageable);
        List<VerificationItem> items = result.getContent().stream().map(this::toItem).toList();
        return PageResult.of(items, page, size, result.getTotalElements());
    }

    @Transactional
    public void approve(UUID verificationId, UUID adminId, AdminRole adminRole, String ip) {
        UserVerification verification = requirePending(verificationId);
        verification.setStatus(VerificationStatus.APPROVED);
        verification.setReviewerId(adminId);
        verification.setReviewedAt(Instant.now());
        verification.setRejectReasonCode(null);
        verification.setRejectNote(null);
        verificationRepository.save(verification);

        contributionService.awardVerificationApproved(verification.getUserId(), verificationId);
        auditService.record(adminId, adminRole, "VERIFICATION_APPROVE", "VERIFICATION",
                verificationId.toString(), null, "核验通过", verificationId.toString(), ip);
    }

    @Transactional
    public void reject(UUID verificationId, String reasonCode, String note,
                       UUID adminId, AdminRole adminRole, String ip) {
        RejectReasonCode code;
        try {
            code = RejectReasonCode.valueOf(reasonCode.trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "驳回原因码不正确");
        }
        UserVerification verification = requirePending(verificationId);
        verification.setStatus(VerificationStatus.REJECTED);
        verification.setRejectReasonCode(code.name());
        verification.setRejectNote(note);
        verification.setReviewerId(adminId);
        verification.setReviewedAt(Instant.now());
        verificationRepository.save(verification);

        UserAccount user = userAccountRepository.findById(verification.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        notificationService.notifyVerificationRejected(user, verificationId, code.name(), reasonLabel(code));

        auditService.record(adminId, adminRole, "VERIFICATION_REJECT", "VERIFICATION",
                verificationId.toString(), code.name(), note, verificationId.toString(), ip);
    }

    private UserVerification requirePending(UUID verificationId) {
        UserVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "佐证记录不存在"));
        if (verification.getStatus() != VerificationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "该佐证已处理");
        }
        return verification;
    }

    private VerificationItem toItem(UserVerification verification) {
        return new VerificationItem(
                verification.getId(),
                verification.getUserId(),
                verification.getProofType(),
                verification.getCompanyUscc(),
                null,
                verification.getPeriodStart(),
                verification.getPeriodEnd(),
                badgeService.badgeOf(verification),
                verification.getStatus(),
                verification.getRejectReasonCode(),
                verification.getRejectNote(),
                verification.getMaterialStatus(),
                verification.getCreatedAt(),
                verification.getReviewedAt());
    }

    private Pageable pageable(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private LocalDate parseMonthStart(String value) {
        YearMonth month = parseMonth(value);
        return month.atDay(1);
    }

    private LocalDate parseMonthEnd(String value) {
        YearMonth month = parseMonth(value);
        return month.atEndOfMonth();
    }

    private YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value.trim());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "在职年月格式应为 YYYY-MM");
        }
    }

    private String resolveContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    /**
     * 图片降采样到 20% 并重写编码（ImageIO 重写会丢弃 EXIF）；非图片返回 null。
     */
    private byte[] downsample(byte[] bytes, String contentType) {
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            return null;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
            if (source == null) {
                return null;
            }
            int width = Math.max(1, source.getWidth() / 5);
            int height = Math.max(1, source.getHeight() / 5);
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String format = contentType.toLowerCase().contains("png") ? "png" : "jpg";
            ImageIO.write(scaled, format, output);
            return output.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("文件哈希计算失败");
        }
    }

    private String reasonLabel(RejectReasonCode code) {
        return switch (code) {
            case MATERIAL_BLURRY -> "材料不清晰";
            case COMPANY_MISMATCH -> "公司与档案不匹配";
            case PERIOD_INVALID -> "在职时间段无效";
            case IDENTITY_MISMATCH -> "身份信息不一致";
            case OTHER -> "其他原因";
        };
    }
}
