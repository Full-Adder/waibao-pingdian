package com.waibao.pingdian.complaint.web;

import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.complaint.domain.ComplaintBlocklist;
import com.waibao.pingdian.complaint.service.ComplaintBlocklistService;
import com.waibao.pingdian.complaint.service.ComplaintRulingService;
import com.waibao.pingdian.complaint.service.ComplaintService;
import com.waibao.pingdian.complaint.web.dto.ComplaintBlocklistRequest;
import com.waibao.pingdian.complaint.web.dto.ComplaintDetailView;
import com.waibao.pingdian.complaint.web.dto.ComplaintEvidenceView;
import com.waibao.pingdian.complaint.web.dto.ComplaintRulingRequest;
import com.waibao.pingdian.complaint.web.dto.ComplaintVerifyRequest;
import com.waibao.pingdian.complaint.web.dto.RulingResult;
import com.waibao.pingdian.security.CurrentAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * 管理端投诉工单：列表、详情、主体核验、举证、裁定与黑名单。
 */
@RestController
@RequestMapping("/api/admin/complaints")
public class AdminComplaintController {

    private final ComplaintService complaintService;
    private final ComplaintRulingService rulingService;
    private final ComplaintBlocklistService blocklistService;
    private final CurrentAdminService currentAdminService;
    private final AdminAuditService auditService;

    public AdminComplaintController(ComplaintService complaintService,
                                    ComplaintRulingService rulingService,
                                    ComplaintBlocklistService blocklistService,
                                    CurrentAdminService currentAdminService,
                                    AdminAuditService auditService) {
        this.complaintService = complaintService;
        this.rulingService = rulingService;
        this.blocklistService = blocklistService;
        this.currentAdminService = currentAdminService;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<PageResult<ComplaintDetailView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(complaintService.list(status, page, size));
    }

    @GetMapping("/{complaintId}")
    public ApiResponse<ComplaintDetailView> detail(@PathVariable UUID complaintId) {
        return ApiResponse.ok(complaintService.detail(complaintId));
    }

    @PostMapping("/{complaintId}/verify")
    public ApiResponse<Void> verify(@PathVariable UUID complaintId,
                                    @RequestBody(required = false) ComplaintVerifyRequest body,
                                    HttpServletRequest request) {
        if (body == null || body.verified() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写 verified");
        }
        AdminUser admin = currentAdminService.getAdmin();
        complaintService.verify(complaintId, body.verified(), body.note(),
                admin.getId(), admin.getRole(), ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping(value = "/{complaintId}/evidence", consumes = "multipart/form-data")
    public ApiResponse<ComplaintEvidenceView> evidence(
            @PathVariable UUID complaintId,
            @RequestParam String party,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String note,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        return ApiResponse.ok(complaintService.addAdminEvidence(complaintId, party, grade, note, file,
                admin.getId(), admin.getRole(), ClientIpResolver.resolve(request)));
    }

    @PostMapping("/{complaintId}/ruling")
    public ResponseEntity<ApiResponse<RulingResult>> ruling(@PathVariable UUID complaintId,
                                                            @RequestBody ComplaintRulingRequest body,
                                                            HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        RulingResult result = rulingService.rule(complaintId, body, admin.getId(), admin.getRole(),
                ClientIpResolver.resolve(request));
        HttpStatus status = result.pendingDualReview() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result));
    }

    @PostMapping("/blocklist")
    public ApiResponse<Map<String, Object>> blocklist(@RequestBody ComplaintBlocklistRequest body,
                                                      HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        if (admin.getRole() != AdminRole.SUPER_ADMIN) {
            auditService.record(admin.getId(), admin.getRole(), "COMPLAINT_BLOCKLIST_DENIED",
                    "COMPLAINT_BLOCKLIST", body == null ? null : body.uscc(), "FORBIDDEN",
                    "内容管理员无权维护投诉黑名单", null, ClientIpResolver.resolve(request));
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅超级管理员可维护投诉黑名单");
        }
        if (body == null || body.uscc() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须填写 uscc");
        }
        ComplaintBlocklist block = blocklistService.add(body.uscc(), body.reason(),
                body.evidenceFile(), body.until(), admin.getId(), admin.getRole(),
                ClientIpResolver.resolve(request));
        return ApiResponse.ok(Map.of("id", block.getId(), "uscc", block.getUscc()));
    }
}
