package com.waibao.pingdian.report.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.report.service.ReportService;
import com.waibao.pingdian.report.web.dto.ReportCreateRequest;
import com.waibao.pingdian.report.web.dto.ReportSubmitView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户举报点评或公司（R15）。需要登录。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReportSubmitView>> submit(@RequestBody ReportCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(reportService.submit(request)));
    }
}
