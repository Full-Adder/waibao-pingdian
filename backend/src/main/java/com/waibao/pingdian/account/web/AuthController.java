package com.waibao.pingdian.account.web;

import com.waibao.pingdian.account.service.AuthService;
import com.waibao.pingdian.account.service.SmsCodeService;
import com.waibao.pingdian.account.web.dto.LoginRequest;
import com.waibao.pingdian.account.web.dto.LoginResult;
import com.waibao.pingdian.account.web.dto.SmsSendRequest;
import com.waibao.pingdian.account.web.dto.SmsSendResult;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SmsCodeService smsCodeService;
    private final AuthService authService;

    public AuthController(SmsCodeService smsCodeService, AuthService authService) {
        this.smsCodeService = smsCodeService;
        this.authService = authService;
    }

    @PostMapping("/sms/send")
    public ApiResponse<SmsSendResult> sendSms(@Valid @RequestBody SmsSendRequest request) {
        return ApiResponse.ok(smsCodeService.send(request.phone().trim(), request.scene()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, ClientIpResolver.resolve(httpRequest)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok(null);
    }
}
