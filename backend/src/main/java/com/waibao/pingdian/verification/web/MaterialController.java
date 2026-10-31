package com.waibao.pingdian.verification.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ClientIpResolver;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.verification.service.MaterialService;
import com.waibao.pingdian.verification.web.dto.MaterialUrlResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping("/{type}/{id}/url")
    public ApiResponse<MaterialUrlResult> signedUrl(@PathVariable String type,
                                                     @PathVariable String id,
                                                     @RequestParam(required = false) String ticketId,
                                                     HttpServletRequest request) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须携带工单号 ticketId");
        }
        return ApiResponse.ok(materialService.signedUrl(type.toUpperCase(), id, ticketId,
                ClientIpResolver.resolve(request)));
    }
}
