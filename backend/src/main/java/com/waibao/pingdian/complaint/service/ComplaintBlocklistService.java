package com.waibao.pingdian.complaint.service;

import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.complaint.domain.ComplaintBlocklist;
import com.waibao.pingdian.complaint.repo.ComplaintBlocklistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 投诉黑名单：主体核验造假或反复恶意投诉的企业 USCC。
 */
@Service
public class ComplaintBlocklistService {

    private final ComplaintBlocklistRepository repository;
    private final AdminAuditService auditService;

    public ComplaintBlocklistService(ComplaintBlocklistRepository repository,
                                     AdminAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public ComplaintBlocklist add(String rawUscc, String reason, String evidenceFile, Instant until,
                                  UUID actorId, AdminRole actorRole, String ip) {
        String uscc = normalizeUscc(rawUscc);
        if (uscc == null || !uscc.matches("^[0-9A-Z]{18}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "USCC 格式不正确");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写拉黑原因");
        }
        if (reason.trim().length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "拉黑原因不能超过 500 字");
        }

        ComplaintBlocklist block = new ComplaintBlocklist();
        block.setUscc(uscc);
        block.setReason(reason.trim());
        block.setEvidenceFile(evidenceFile);
        block.setUntil(until);
        repository.save(block);

        auditService.record(actorId, actorRole, "COMPLAINT_BLOCKLIST_ADD", "COMPLAINT_BLOCKLIST",
                uscc, "MALICIOUS_COMPLAINT", block.getReason(), null, ip);
        return block;
    }

    /**
     * 有效期内（until 为空表示永久）即视为拉黑。
     */
    public boolean isBlocked(String rawUscc) {
        String uscc = normalizeUscc(rawUscc);
        if (uscc == null) {
            return false;
        }
        Instant now = Instant.now();
        return repository.findByUscc(uscc).stream()
                .anyMatch(block -> block.getUntil() == null || block.getUntil().isAfter(now));
    }

    public static String normalizeUscc(String uscc) {
        return uscc == null ? null : uscc.trim().toUpperCase();
    }
}
