package com.waibao.pingdian.audit.web;

import com.waibao.pingdian.account.domain.AdminRole;
import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.audit.service.AdminAuditService;
import com.waibao.pingdian.audit.web.dto.AuditLogItem;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.security.CurrentAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 审计日志查询（R17/R19），仅 SUPER_ADMIN 可访问，不提供修改与删除。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AdminAuditService auditService;
    private final CurrentAdminService currentAdminService;

    public AdminAuditController(AdminAuditService auditService,
                                CurrentAdminService currentAdminService) {
        this.auditService = auditService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping
    public ApiResponse<PageResult<AuditLogItem>> list(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminUser admin = currentAdminService.getAdmin();
        if (admin.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅超级管理员可查询审计日志");
        }
        return ApiResponse.ok(auditService.list(actorId, action, targetType, targetId,
                from, to, page, size));
    }
}
