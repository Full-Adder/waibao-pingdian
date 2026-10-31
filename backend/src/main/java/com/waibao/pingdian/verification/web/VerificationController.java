package com.waibao.pingdian.verification.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.verification.domain.ProofType;
import com.waibao.pingdian.verification.service.VerificationService;
import com.waibao.pingdian.verification.web.dto.VerificationItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/verifications")
public class VerificationController {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping
    public ApiResponse<VerificationItem> submit(@RequestParam ProofType proofType,
                                                 @RequestParam String companyUscc,
                                                 @RequestParam String periodStart,
                                                 @RequestParam String periodEnd,
                                                 @RequestParam MultipartFile file) {
        return ApiResponse.ok(verificationService.submit(
                proofType, companyUscc, periodStart, periodEnd, file));
    }

    @GetMapping({"/mine", ""})
    public ApiResponse<PageResult<VerificationItem>> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(verificationService.listMine(page, size));
    }
}
