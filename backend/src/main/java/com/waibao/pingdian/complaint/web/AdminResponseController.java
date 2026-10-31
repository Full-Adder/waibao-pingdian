package com.waibao.pingdian.complaint.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.complaint.service.EnterpriseResponseService;
import com.waibao.pingdian.complaint.web.dto.AdminResponseItem;
import com.waibao.pingdian.complaint.web.dto.ResponseRejectRequest;
import com.waibao.pingdian.security.CurrentAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 管理端企业回应审核。企业侧无删除接口。
 */
@RestController
@RequestMapping("/api/admin/responses")
public class AdminResponseController {

    private final EnterpriseResponseService responseService;
    private final CurrentAdminService currentAdminService;

    public AdminResponseController(EnterpriseResponseService responseService,
                                   CurrentAdminService currentAdminService) {
        this.responseService = responseService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminResponseItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(responseService.listForAdmin(status, page, size));
    }

    @PostMapping("/{responseId}/approve")
    public ApiResponse<Void> approve(@PathVariable UUID responseId, HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        responseService.approve(responseId, admin.getId(), admin.getRole(),
                ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{responseId}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID responseId,
                                    @RequestBody(required = false) ResponseRejectRequest body,
                                    HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        responseService.reject(responseId, body == null ? null : body.effectiveReason(),
                body == null ? null : body.note(), admin.getId(), admin.getRole(),
                ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }
}
