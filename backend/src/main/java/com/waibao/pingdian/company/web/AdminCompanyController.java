package com.waibao.pingdian.company.web;

import com.waibao.pingdian.account.domain.AdminUser;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.domain.CandidateStatus;
import com.waibao.pingdian.company.service.AdminCompanyService;
import com.waibao.pingdian.company.service.CompanyProfileService;
import com.waibao.pingdian.company.web.dto.CandidateResolveRequest;
import com.waibao.pingdian.company.web.dto.CompanyCandidateItem;
import com.waibao.pingdian.company.web.dto.CompanyDetailResponse;
import com.waibao.pingdian.company.web.dto.CompanySummary;
import com.waibao.pingdian.company.web.dto.CompanyUpdateRequest;
import com.waibao.pingdian.company.web.dto.MergeCompanyRequest;
import com.waibao.pingdian.company.web.dto.MergePathRequest;
import com.waibao.pingdian.security.CurrentAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminCompanyController {

    private final AdminCompanyService adminCompanyService;
    private final CompanyProfileService companyProfileService;
    private final CurrentAdminService currentAdminService;

    public AdminCompanyController(AdminCompanyService adminCompanyService,
                                  CompanyProfileService companyProfileService,
                                  CurrentAdminService currentAdminService) {
        this.adminCompanyService = adminCompanyService;
        this.companyProfileService = companyProfileService;
        this.currentAdminService = currentAdminService;
    }

    @GetMapping("/companies")
    public ApiResponse<PageResult<CompanySummary>> listCompanies(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String industry,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminCompanyService.listCompanies(
                keyword, status, city, industry, page, size));
    }

    @GetMapping("/companies/{uscc}")
    public ApiResponse<CompanyDetailResponse> companyDetail(@PathVariable String uscc) {
        return ApiResponse.ok(companyProfileService.detail(uscc));
    }

    @PutMapping("/companies/{uscc}")
    public ApiResponse<Void> updateCompany(@PathVariable String uscc,
                                           @Valid @RequestBody CompanyUpdateRequest body,
                                           HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        adminCompanyService.updateCompany(uscc, body, admin, ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PatchMapping("/companies/{uscc}")
    public ApiResponse<Void> patchCompany(@PathVariable String uscc,
                                          @Valid @RequestBody CompanyUpdateRequest body,
                                          HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        adminCompanyService.updateCompany(uscc, body, admin, ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/companies/merge")
    public ApiResponse<Void> mergeCompanies(@Valid @RequestBody MergeCompanyRequest body,
                                            HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        adminCompanyService.mergeCompanies(body.sourceUscc(), body.targetUscc(), body.reason(),
                body.ticketId(), admin, ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/companies/{sourceUscc}/merge-into/{targetUscc}")
    public ApiResponse<Void> mergeCompaniesInto(@PathVariable String sourceUscc,
                                                @PathVariable String targetUscc,
                                                @Valid @RequestBody MergePathRequest body,
                                                HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        adminCompanyService.mergeCompanies(sourceUscc, targetUscc, body.reason(), body.ticketId(),
                admin, ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/company-candidates")
    public ApiResponse<PageResult<CompanyCandidateItem>> listCandidates(
            @RequestParam(required = false) CandidateStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminCompanyService.listCandidates(status, page, size));
    }

    @PostMapping("/company-candidates/{candidateId}/match")
    public ApiResponse<Void> matchCandidate(@PathVariable UUID candidateId,
                                            @RequestBody(required = false) CandidateResolveRequest body,
                                            HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        CandidateResolveRequest payload = body == null
                ? new CandidateResolveRequest(null, null, null, null, null, null, null)
                : body;
        adminCompanyService.matchCandidate(candidateId, payload.uscc(), payload.note(),
                payload.ticketId(), admin, ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/company-candidates/{candidateId}/create")
    public ApiResponse<String> createCandidate(@PathVariable UUID candidateId,
                                               @RequestBody(required = false) CandidateResolveRequest body,
                                               HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        String uscc = adminCompanyService.createCompanyFromCandidate(
                candidateId, body, admin, ClientIpResolver.resolve(request));
        return ApiResponse.ok(uscc);
    }

    @PostMapping("/company-candidates/{candidateId}/reject")
    public ApiResponse<Void> rejectCandidate(@PathVariable UUID candidateId,
                                             @RequestBody(required = false) CandidateResolveRequest body,
                                             HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        CandidateResolveRequest payload = body == null
                ? new CandidateResolveRequest(null, null, null, null, null, null, null)
                : body;
        adminCompanyService.rejectCandidate(candidateId, payload.note(), payload.ticketId(),
                admin, ClientIpResolver.resolve(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/company-candidates/{candidateId}/resolve")
    public ApiResponse<Void> resolveCandidate(@PathVariable UUID candidateId,
                                              @Valid @RequestBody CandidateResolveRequest body,
                                              HttpServletRequest request) {
        AdminUser admin = currentAdminService.getAdmin();
        String action = body.action() == null ? "" : body.action().trim().toUpperCase();
        switch (action) {
            case "MATCH" -> adminCompanyService.matchCandidate(candidateId, body.uscc(), body.note(),
                    body.ticketId(), admin, ClientIpResolver.resolve(request));
            case "CREATE" -> adminCompanyService.createCompanyFromCandidate(
                    candidateId, body, admin, ClientIpResolver.resolve(request));
            case "REJECT" -> adminCompanyService.rejectCandidate(candidateId, body.note(),
                    body.ticketId(), admin, ClientIpResolver.resolve(request));
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "候选处理动作不正确");
        }
        return ApiResponse.ok(null);
    }
}
