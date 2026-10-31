package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.account.web.dto.RealnameRequest;
import com.waibao.pingdian.account.web.dto.RealnameResult;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.provider.RealnameProvider;
import com.waibao.pingdian.security.CryptoService;
import com.waibao.pingdian.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 身份证二要素核验：连续失败达阈值锁定 24 小时；只持久化核验状态，不落证件号明文。
 */
@Service
public class RealnameService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter UNLOCK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BUSINESS_ZONE);

    private final CurrentUserService currentUserService;
    private final UserAccountRepository userAccountRepository;
    private final RealnameProvider realnameProvider;
    private final CryptoService cryptoService;
    private final int maxFailures;
    private final int lockHours;

    public RealnameService(CurrentUserService currentUserService,
                           UserAccountRepository userAccountRepository,
                           RealnameProvider realnameProvider,
                           CryptoService cryptoService,
                           @Value("${realname.max-failures:3}") int maxFailures,
                           @Value("${realname.lock-hours:24}") int lockHours) {
        this.currentUserService = currentUserService;
        this.userAccountRepository = userAccountRepository;
        this.realnameProvider = realnameProvider;
        this.cryptoService = cryptoService;
        this.maxFailures = maxFailures;
        this.lockHours = lockHours;
    }

    @Transactional
    public RealnameResult verify(RealnameRequest request) {
        UserAccount user = currentUserService.getUser();
        Instant now = Instant.now();

        if (user.getRealnameLockedUntil() != null && now.isBefore(user.getRealnameLockedUntil())) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "实名认证已锁定，解锁时间：" + UNLOCK_FORMAT.format(user.getRealnameLockedUntil()));
        }
        if (user.getRealnameLockedUntil() != null) {
            user.setRealnameLockedUntil(null);
            user.setRealnameFailCount(0);
        }

        String idNumber = request.idNumber().trim();
        RealnameProvider.RealnameResult providerResult =
                realnameProvider.verify(request.name().trim(), idNumber);

        if (providerResult.success()) {
            user.setRealnameStatus(RealnameStatus.VERIFIED);
            user.setRealnameVerifiedAt(now);
            user.setRealnameFailCount(0);
            user.setRealnameLockedUntil(null);
            // 只落证件号 HMAC 与掩码，不存明文。
            user.setIdHash(cryptoService.hmacSha256Hex(idNumber));
            user.setIdMasked(cryptoService.maskedIdNumber(idNumber));
            userAccountRepository.save(user);
            return new RealnameResult(true, user.getRealnameStatus(), 0, null, null,
                    user.getIdMasked());
        }

        user.setRealnameFailCount(user.getRealnameFailCount() + 1);
        if (user.getRealnameFailCount() >= maxFailures) {
            user.setRealnameLockedUntil(now.plusSeconds(lockHours * 3600L));
        }
        userAccountRepository.save(user);
        return new RealnameResult(false, user.getRealnameStatus(), user.getRealnameFailCount(),
                user.getRealnameLockedUntil(), providerResult.reasonCode(),
                cryptoService.maskedIdNumber(idNumber));
    }
}
