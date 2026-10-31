package com.waibao.pingdian.account.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.account.service.AdminUserService;
import com.waibao.pingdian.account.web.dto.AdminUserDetail;
import com.waibao.pingdian.account.web.dto.AdminUserListItem;
import com.waibao.pingdian.account.web.dto.BanUserRequest;
import com.waibao.pingdian.account.web.dto.ContributionAdjustRequest;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.security.CurrentAdminService;
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

/**
 * 管理端用户管理（R17）。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CurrentAdminService currentAdminService;

    public AdminUserController(AdminUserService adminUserService,
                               CurrentAdminService currentAdminService) {
        this.adminUserService = adminUserService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminUserListItem>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminUserService.list(keyword, status, page, size));
    }

    @GetMapping("/{userId}")
    public ApiResponse<AdminUserDetail> detail(@PathVariable UUID userId) {
        return ApiResponse.ok(adminUserService.detail(userId));
    }

    @PostMapping("/{userId}/ban")
    public ApiResponse<Void> ban(@PathVariable UUID userId,
                                 @Valid @RequestBody BanUserRequest request,
                                 HttpServletRequest httpRequest) {
        AdminUser admin = currentAdminService.getAdmin();
        adminUserService.ban(userId, request, admin, ClientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{userId}/contribution")
    public ApiResponse<Void> adjustContribution(@PathVariable UUID userId,
                                                @Valid @RequestBody ContributionAdjustRequest request,
                                                HttpServletRequest httpRequest) {
        AdminUser admin = currentAdminService.getAdmin();
        adminUserService.adjustContribution(userId, request, admin, ClientIpResolver.resolve(httpRequest));
        return ApiResponse.ok(null);
    }
}
