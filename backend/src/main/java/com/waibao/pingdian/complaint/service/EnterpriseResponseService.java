package com.waibao.pingdian.complaint.service;

import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.complaint.domain.Complaint;
import com.waibao.pingdian.complaint.domain.EnterpriseResponse;
import com.waibao.pingdian.complaint.domain.EnterpriseResponseStatus;
import com.waibao.pingdian.complaint.repo.ComplaintRepository;
import com.waibao.pingdian.complaint.repo.EnterpriseResponseRepository;
import com.waibao.pingdian.complaint.web.dto.AdminResponseItem;
import com.waibao.pingdian.complaint.web.dto.EnterpriseResponseSubmitRequest;
import com.waibao.pingdian.complaint.web.dto.EnterpriseResponseView;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.repo.ReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 企业回应（F26）：凭工单提交、联系方式过滤、审核通过后公开；不计分、不改点评状态、不可删除。
 */
@Service
public class EnterpriseResponseService {

    private static final int MAX_CONTENT = 500;
    private static final int MAX_PAGE_SIZE = 100;

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile(
            "(https?://|www\\.)[^\\s]+|\\b[A-Za-z0-9-]+(\\.com|\\.cn|\\.net|\\.org)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IM_CONTACT = Pattern.compile(
            "微信|weixin|wechat|QQ号|qq号|企鹅号|加我|私聊|联系我|扫码", Pattern.CASE_INSENSITIVE);

    private final EnterpriseResponseRepository responseRepository;
    private final ComplaintRepository complaintRepository;
    private final ReviewRepository reviewRepository;
    private final AdminAuditService auditService;

    public EnterpriseResponseService(EnterpriseResponseRepository responseRepository,
                                     ComplaintRepository complaintRepository,
                                     ReviewRepository reviewRepository,
                                     AdminAuditService auditService) {
        this.responseRepository = responseRepository;
        this.complaintRepository = complaintRepository;
        this.reviewRepository = reviewRepository;
        this.auditService = auditService;
    }

    @Transactional
    public UUID submit(String rawUscc, EnterpriseResponseSubmitRequest request) {
        String uscc = ComplaintBlocklistService.normalizeUscc(rawUscc);
        if (uscc == null || !uscc.matches("^[0-9A-Z]{18}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "USCC 格式不正确");
        }
        if (request == null || request.ticketId() == null || request.ticketId().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写投诉工单号 ticketId");
        }
        UUID complaintId;
        try {
            complaintId = UUID.fromString(request.ticketId().trim());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "投诉工单号格式不正确");
        }
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投诉工单不存在"));
        if (!complaint.isSubjectVerified()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "主体核验通过后才能提交企业回应");
        }
        if (!uscc.equals(complaint.getClaimantUscc())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "USCC 与投诉工单主体不一致");
        }

        String content = request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回应内容不能为空");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回应内容不能超过 " + MAX_CONTENT + " 字");
        }
        if (containsContact(content)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回应不得包含手机号、微信、QQ、邮箱或网址等联系方式");
        }

        Review review = reviewRepository.findById(complaint.getReviewId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));

        EnterpriseResponse response = new EnterpriseResponse();
        response.setReviewId(review.getId());
        response.setComplaintId(complaint.getId());
        response.setUscc(uscc);
        response.setContent(content);
        response.setStatus(EnterpriseResponseStatus.PENDING);
        responseRepository.save(response);
        return response.getId();
    }

    /**
     * 公开读取：只返回审核通过的企业回应。
     */
    public List<EnterpriseResponseView> approved(String rawUscc, String reviewId) {
        String uscc = ComplaintBlocklistService.normalizeUscc(rawUscc);
        if (uscc == null || !uscc.matches("^[0-9A-Z]{18}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "USCC 格式不正确");
        }
        if (reviewId != null && !reviewId.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(reviewId.trim());
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "reviewId 格式不正确");
            }
            Review review = reviewRepository.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
            if (!uscc.equals(review.getUscc())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "点评不属于该公司");
            }
            return approvedOf(id);
        }
        return responseRepository.findByUsccAndStatusOrderByCreatedAtDesc(uscc,
                        EnterpriseResponseStatus.APPROVED).stream()
                .map(this::toPublicView)
                .toList();
    }

    public List<EnterpriseResponseView> approvedOf(UUID reviewId) {
        return responseRepository.findByReviewIdAndStatusOrderByCreatedAtDesc(
                        reviewId, EnterpriseResponseStatus.APPROVED).stream()
                .map(this::toPublicView)
                .toList();
    }

    public Optional<EnterpriseResponseView> firstApproved(UUID reviewId) {
        return responseRepository.findFirstByReviewIdAndStatusOrderByCreatedAtDesc(
                        reviewId, EnterpriseResponseStatus.APPROVED).map(this::toPublicView);
    }

    public List<EnterpriseResponseView> allOf(UUID reviewId) {
        return responseRepository.findByReviewIdOrderByCreatedAtDesc(reviewId).stream()
                .map(this::toPublicView)
                .toList();
    }

    public PageResult<AdminResponseItem> listForAdmin(String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        EnterpriseResponseStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = EnterpriseResponseStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "回应状态不正确");
            }
        }
        Page<EnterpriseResponse> result = filter == null
                ? responseRepository.findAll(pageable)
                : responseRepository.findByStatus(filter, pageable);
        List<AdminResponseItem> items = result.getContent().stream()
                .map(response -> new AdminResponseItem(
                        response.getId(), response.getReviewId(), response.getComplaintId(),
                        response.getUscc(), response.getContent(), response.getStatus(),
                        response.getRejectReason(), response.getCreatedAt(), response.getReviewedAt()))
                .toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    @Transactional
    public void approve(UUID responseId, UUID adminId, AdminRole adminRole, String ip) {
        EnterpriseResponse response = requirePending(responseId);
        response.setStatus(EnterpriseResponseStatus.APPROVED);
        response.setReviewerId(adminId);
        response.setReviewedAt(Instant.now());
        response.setRejectReason(null);
        responseRepository.save(response);

        auditService.record(adminId, adminRole, "RESPONSE_APPROVE", "ENTERPRISE_RESPONSE",
                responseId.toString(), null, "企业回应审核通过", response.getComplaintId() == null
                        ? null : response.getComplaintId().toString(), ip);
    }

    @Transactional
    public void reject(UUID responseId, String reason, String note,
                       UUID adminId, AdminRole adminRole, String ip) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写 reject reason");
        }
        String message = note == null || note.isBlank() ? reason.trim() : reason.trim() + "：" + note.trim();
        EnterpriseResponse response = requirePending(responseId);
        response.setStatus(EnterpriseResponseStatus.REJECTED);
        response.setReviewerId(adminId);
        response.setReviewedAt(Instant.now());
        response.setRejectReason(message);
        responseRepository.save(response);

        auditService.record(adminId, adminRole, "RESPONSE_REJECT", "ENTERPRISE_RESPONSE",
                responseId.toString(), "CONTACT_OR_CONTENT", message,
                response.getComplaintId() == null ? null : response.getComplaintId().toString(), ip);
    }

    private EnterpriseResponse requirePending(UUID responseId) {
        EnterpriseResponse response = responseRepository.findById(responseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "企业回应不存在"));
        if (response.getStatus() != EnterpriseResponseStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "该企业回应已审核");
        }
        return response;
    }

    private EnterpriseResponseView toPublicView(EnterpriseResponse response) {
        return EnterpriseResponseView.approved(response.getId(), response.getReviewId(),
                response.getContent(), response.getCreatedAt());
    }

    private boolean containsContact(String content) {
        return PHONE.matcher(content).find()
                || EMAIL.matcher(content).find()
                || URL.matcher(content).find()
                || IM_CONTACT.matcher(content).find();
    }
}
