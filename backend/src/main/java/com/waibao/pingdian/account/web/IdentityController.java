package com.waibao.pingdian.account.web;

import com.waibao.pingdian.account.service.IdentityService;
import com.waibao.pingdian.account.web.dto.IdentityChangeResult;
import com.waibao.pingdian.account.web.dto.IdentityDetail;
import com.waibao.pingdian.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/identity")
public class IdentityController {

    private final IdentityService identityService;

    public IdentityController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping
    public ApiResponse<IdentityDetail> current() {
        return ApiResponse.ok(identityService.currentIdentity());
    }

    @PostMapping("/change")
    public ApiResponse<IdentityChangeResult> change() {
        return ApiResponse.ok(identityService.changeIdentity());
    }
}
