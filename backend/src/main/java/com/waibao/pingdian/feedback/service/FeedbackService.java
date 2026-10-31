package com.waibao.pingdian.feedback.service;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.common.SensitiveDataMasker;
import com.waibao.pingdian.feedback.domain.FeedbackCategory;
import com.waibao.pingdian.feedback.domain.FeedbackStatus;
import com.waibao.pingdian.feedback.domain.FeedbackTicket;
import com.waibao.pingdian.feedback.repo.FeedbackTicketRepository;
import com.waibao.pingdian.feedback.web.dto.FeedbackItemView;
import com.waibao.pingdian.feedback.web.dto.FeedbackReplyRequest;
import com.waibao.pingdian.feedback.web.dto.FeedbackSubmitRequest;
import com.waibao.pingdian.feedback.web.dto.FeedbackSubmitResult;
import com.waibao.pingdian.security.CryptoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 帮助与反馈工单（F28）：公开提交、管理端查看与回复。
 */
@Service
public class FeedbackService {

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_CONTENT = 10;
    private static final int MAX_CONTENT = 2000;
    private static final int MIN_NOTE = 5;
    private static final int MAX_NOTE = 1000;

    private final FeedbackTicketRepository ticketRepository;
    private final CryptoService cryptoService;
    private final AdminAuditService auditService;

    public FeedbackService(FeedbackTicketRepository ticketRepository,
                           CryptoService cryptoService,
                           AdminAuditService auditService) {
        this.ticketRepository = ticketRepository;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    @Transactional
    public FeedbackSubmitResult submit(FeedbackSubmitRequest request) {
        String content = request.content() == null ? "" : request.content().trim();
        if (content.length() < MIN_CONTENT || content.length() > MAX_CONTENT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "反馈内容需为 10–2000 字");
        }
        String contact = request.contact() == null ? null : request.contact().trim();
        if (contact != null && contact.isEmpty()) {
            contact = null;
        }

        FeedbackTicket ticket = new FeedbackTicket();
        ticket.setCategory(request.category() == null ? FeedbackCategory.OTHER : request.category());
        ticket.setContent(content);
        ticket.setContactCipher(contact == null ? null : cryptoService.encrypt(contact));
        ticket.setStatus(FeedbackStatus.PENDING);
        ticketRepository.save(ticket);
        return new FeedbackSubmitResult(ticket.getId(), ticket.getStatus());
    }

    public PageResult<FeedbackItemView> list(String rawStatus, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<FeedbackTicket> result;
        if (rawStatus == null || rawStatus.isBlank()) {
            result = ticketRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            FeedbackStatus status;
            try {
                status = FeedbackStatus.valueOf(rawStatus.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "工单状态不正确");
            }
            result = ticketRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        List<FeedbackItemView> items = result.getContent().stream().map(this::toView).toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    @Transactional
    public void reply(UUID ticketId, FeedbackReplyRequest request,
                      AdminUser admin, String ip) {
        FeedbackTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "反馈工单不存在"));
        if (ticket.getStatus() == FeedbackStatus.CLOSED) {
            throw new BusinessException(ErrorCode.CONFLICT, "该工单已关闭");
        }
        String note = request.responseNote() == null ? "" : request.responseNote().trim();
        if (note.length() < MIN_NOTE || note.length() > MAX_NOTE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回复内容需为 5–1000 字");
        }

        FeedbackStatus target = Boolean.TRUE.equals(request.close())
                ? FeedbackStatus.CLOSED
                : FeedbackStatus.REPLIED;
        ticket.setStatus(target);
        ticket.setResponseNote(note);
        ticket.setRespondedBy(admin.getId());
        ticket.setRespondedAt(Instant.now());
        ticketRepository.save(ticket);

        auditService.record(admin.getId(), admin.getRole(), "FEEDBACK_REPLY", "FEEDBACK",
                ticket.getId().toString(), null, note, ticket.getId().toString(), ip);
    }

    private FeedbackItemView toView(FeedbackTicket ticket) {
        return new FeedbackItemView(
                ticket.getId(),
                ticket.getCategory(),
                maskedContact(ticket.getContactCipher()),
                ticket.getContent(),
                ticket.getStatus(),
                ticket.getResponseNote(),
                ticket.getRespondedBy(),
                ticket.getRespondedAt(),
                ticket.getCreatedAt());
    }

    /**
     * 联系方式解密后仅返回掩码；解密失败返回占位符，绝不输出密文或明文。
     */
    private String maskedContact(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            String plain = cryptoService.decrypt(cipher);
            if (PHONE.matcher(plain).matches()) {
                return SensitiveDataMasker.maskPhone(plain);
            }
            return SensitiveDataMasker.mask(plain);
        } catch (RuntimeException ex) {
            return "***";
        }
    }
}
