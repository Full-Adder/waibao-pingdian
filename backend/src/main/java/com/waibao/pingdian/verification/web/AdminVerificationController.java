package com.waibao.pingdian.verification.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.security.CurrentAdminService;
import com.waibao.pingdian.verification.domain.VerificationStatus;
import com.waibao.pingdian.verification.service.VerificationService;
import com.waibao.pingdian.verification.web.dto.VerificationItem;
import com.waibao.pingdian.verification.web.dto.VerificationRejectRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/verifications")
public class AdminVerificationController {

    private final VerificationService verificationService;
    private final CurrentAdminService currentAdminService;

    public AdminVerificationController(VerificationService verificationService,
                                       CurrentAdminService currentAdminService) {
        this.verificationService = verificationService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping
    public ApiResponse<PageResult<VerificationItem>> list(
            @RequestParam(required = false) VerificationStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(verificationService.listForAdmin(status, page, size));
    }

    @PostMapping("/{verificationId}/approve")
    public ApiResponse<Void> approve(@PathVariable UUID verificationId, HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        verificationService.approve(verificationId, admin.getId(), admin.getRole(),
                ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{verificationId}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID verificationId,
                                    @Valid @RequestBody VerificationRejectRequest body,
                                    HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        verificationService.reject(verificationId, body.reasonCode(), body.note(),
                admin.getId(), admin.getRole(), ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }
}
