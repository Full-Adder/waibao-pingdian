package com.waibao.pingdian.complaint.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.complaint.service.ComplaintService;
import com.waibao.pingdian.complaint.web.dto.ComplaintCreatedResult;
import com.waibao.pingdian.complaint.web.dto.ComplaintEvidenceView;
import com.waibao.pingdian.complaint.web.dto.MyComplaintView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 企业公开申诉与作者侧举证/工单查询。
 */
@RestController
@RequestMapping("/api")
public class ComplaintController {

    private final ComplaintService complaintService;

    public ComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @PostMapping(value = "/appeals", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<ComplaintCreatedResult>> appeal(
            @RequestParam String uscc,
            @RequestParam UUID reviewId,
            @RequestParam String contact,
            @RequestParam("licenseFile") MultipartFile licenseFile,
            @RequestParam("letterFile") MultipartFile letterFile,
            @RequestParam(required = false) Boolean amountFlag,
            @RequestParam(required = false) Boolean safetyFlag,
            HttpServletRequest request) {
        ComplaintCreatedResult result = complaintService.submitAppeal(uscc, reviewId, contact,
                licenseFile, letterFile, Boolean.TRUE.equals(amountFlag),
                Boolean.TRUE.equals(safetyFlag), ClientIpResolver.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    @PostMapping(value = "/complaints/{complaintId}/evidence", consumes = "multipart/form-data")
    public ApiResponse<ComplaintEvidenceView> uploadEvidence(
            @PathVariable UUID complaintId,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String note,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(complaintService.addUserEvidence(complaintId, grade, note, file));
    }

    @GetMapping("/complaints/mine/{reviewId}")
    public ApiResponse<MyComplaintView> mine(@PathVariable UUID reviewId) {
        return ApiResponse.ok(complaintService.mine(reviewId));
    }
}
