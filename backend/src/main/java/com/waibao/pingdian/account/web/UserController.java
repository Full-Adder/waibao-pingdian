package com.waibao.pingdian.account.web;

import com.waibao.pingdian.account.service.AuthService;
import com.waibao.pingdian.account.service.RealnameService;
import com.waibao.pingdian.account.web.dto.RealnameRequest;
import com.waibao.pingdian.account.web.dto.RealnameResult;
import com.waibao.pingdian.account.web.dto.UserProfile;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.verification.service.VerificationService;
import com.waibao.pingdian.verification.web.dto.MaterialDeletionResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;
    private final RealnameService realnameService;
    private final VerificationService verificationService;

    public UserController(AuthService authService, RealnameService realnameService,
                          VerificationService verificationService) {
        this.authService = authService;
        this.realnameService = realnameService;
        this.verificationService = verificationService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> me() {
        return ApiResponse.ok(authService.currentProfile());
    }

    @PostMapping("/me/realname")
    public ApiResponse<RealnameResult> verifyRealname(@Valid @RequestBody RealnameRequest request) {
        return ApiResponse.ok(realnameService.verify(request));
    }

    @PostMapping("/me/verifications/{verificationId}/delete-material")
    public ApiResponse<MaterialDeletionResult> deleteVerificationMaterial(
            @PathVariable UUID verificationId) {
        return ApiResponse.ok(verificationService.deleteMaterial(verificationId));
    }
}
