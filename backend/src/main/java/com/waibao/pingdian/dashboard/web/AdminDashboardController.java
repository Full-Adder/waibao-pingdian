package com.waibao.pingdian.dashboard.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.dashboard.service.DashboardService;
import com.waibao.pingdian.dashboard.web.dto.DashboardResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端数据看板（R18）。内容管理员及以上（ADMIN）可访问。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final DashboardService dashboardService;

    public AdminDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardResponse> dashboard(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String industry,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(dashboardService.dashboard(from, to, city, industry, refresh));
    }
}
