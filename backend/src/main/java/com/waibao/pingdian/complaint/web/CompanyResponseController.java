package com.waibao.pingdian.complaint.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.complaint.service.EnterpriseResponseService;
import com.waibao.pingdian.complaint.web.dto.EnterpriseResponseSubmitRequest;
import com.waibao.pingdian.complaint.web.dto.EnterpriseResponseView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 公开企业回应：凭投诉工单提交；审核通过后展示。
 */
@RestController
@RequestMapping("/api/companies/{uscc}/responses")
public class CompanyResponseController {

    private final EnterpriseResponseService responseService;

    public CompanyResponseController(EnterpriseResponseService responseService) {
        this.responseService = responseService;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> submit(@PathVariable String uscc,
                                                   @RequestBody EnterpriseResponseSubmitRequest body) {
        responseService.submit(uscc, body);
        return ApiResponse.ok(Map.of("status", "PENDING"));
    }

    @GetMapping
    public ApiResponse<List<EnterpriseResponseView>> list(@PathVariable String uscc,
                                                          @RequestParam(required = false) String reviewId) {
        return ApiResponse.ok(responseService.approved(uscc, reviewId));
    }
}
