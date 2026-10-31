package com.waibao.pingdian.company.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.service.CompanyProfileService;
import com.waibao.pingdian.company.service.CompanyReportService;
import com.waibao.pingdian.company.service.CompanySearchService;
import com.waibao.pingdian.company.web.dto.CompanyDetailResponse;
import com.waibao.pingdian.company.web.dto.CompanyReportRequest;
import com.waibao.pingdian.company.web.dto.CompanyReportResult;
import com.waibao.pingdian.company.web.dto.CompanySummary;
import com.waibao.pingdian.company.web.dto.SitemapEntry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanySearchService searchService;
    private final CompanyProfileService profileService;
    private final CompanyReportService reportService;

    public CompanyController(CompanySearchService searchService,
                             CompanyProfileService profileService,
                             CompanyReportService reportService) {
        this.searchService = searchService;
        this.profileService = profileService;
        this.reportService = reportService;
    }

    @GetMapping("/search")
    public ApiResponse<PageResult<CompanySummary>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String industry,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String visitorId,
            @RequestHeader(value = "X-Visitor-Id", required = false) String visitorIdHeader) {
        String visitor = visitorIdHeader != null && !visitorIdHeader.isBlank()
                ? visitorIdHeader : visitorId;
        return ApiResponse.ok(searchService.search(keyword, city, industry, page, size, visitor));
    }

    @GetMapping("/sitemap")
    public ApiResponse<List<SitemapEntry>> sitemap(@RequestParam(required = false) String city,
                                                   @RequestParam(required = false) String industry) {
        return ApiResponse.ok(profileService.sitemap(city, industry));
    }

    @GetMapping("/{uscc}")
    public ApiResponse<CompanyDetailResponse> detail(@PathVariable String uscc) {
        return ApiResponse.ok(profileService.detail(uscc));
    }

    @PostMapping("/report")
    public ApiResponse<CompanyReportResult> report(@Valid @RequestBody CompanyReportRequest request) {
        return ApiResponse.ok(reportService.report(request));
    }
}
