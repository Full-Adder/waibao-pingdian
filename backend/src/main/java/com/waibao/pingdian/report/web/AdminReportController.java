package com.waibao.pingdian.report.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.report.service.ReportService;
import com.waibao.pingdian.report.web.dto.ReportItemView;
import com.waibao.pingdian.report.web.dto.ReportResolveRequest;
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
 * 管理端举报处置列表与处置（R17）。
 */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final ReportService reportService;
    private final CurrentAdminService currentAdminService;

    public AdminReportController(ReportService reportService,
                                 CurrentAdminService currentAdminService) {
        this.reportService = reportService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping
    public ApiResponse<PageResult<ReportItemView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reportService.list(status, page, size));
    }

    @PostMapping("/{reportId}/resolve")
    public ApiResponse<Void> resolve(@PathVariable UUID reportId,
                                     @RequestBody(required = false) ReportResolveRequest body,
                                     HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        reportService.resolve(reportId, body, admin.getId(), admin.getRole(),
                ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }
}
