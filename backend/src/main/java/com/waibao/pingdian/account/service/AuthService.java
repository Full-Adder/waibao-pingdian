package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.domain.AnonymousIdentity;
import com.waibao.pingdian.account.domain.IdentityStatus;
import com.waibao.pingdian.account.domain.RealnameStatus;
import com.waibao.pingdian.account.domain.RiskSubjectType;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.domain.UserStatus;
import com.waibao.pingdian.account.repo.AnonymousIdentityRepository;
import com.waibao.pingdian.account.repo.UserAccountRepository;
import com.waibao.pingdian.account.web.dto.IdentityInfo;
import com.waibao.pingdian.account.web.dto.LoginRequest;
import com.waibao.pingdian.account.web.dto.LoginResult;
import com.waibao.pingdian.account.web.dto.UserProfile;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.security.CryptoService;
import com.waibao.pingdian.security.CurrentUserService;
import com.waibao.pingdian.security.JwtService;
import com.waibao.pingdian.security.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * 用户注册 / 登录 / 退出。注册与匿名身份创建在同一事务；每个账号只保留最新 jti 实现单设备会话。
 * 最新 jti 同时写入 Redis（多实例共享）与数据库 session_jti（审计/Redis 故障回退）。
 */
@Service
public class AuthService {

    private static final Duration RISK_TTL = Duration.ofHours(24);
    private static final String SIGNAL_REGISTRATION = "ACCOUNT_REGISTRATION";
    private static final String SIGNAL_MULTI_ACCOUNT = "SHORT_TERM_MULTI_ACCOUNT";
    private static final String SIGNAL_LOGIN_FAILED = "LOGIN_CODE_FAILED";

    private final SmsCodeService smsCodeService;
    private final RegistrationLimitService registrationLimitService;
    private final RiskSignalService riskSignalService;
    private final AnonymousAliasGenerator aliasGenerator;
    private final CryptoService cryptoService;
    private final JwtService jwtService;
    private final CurrentUserService currentUserService;
    private final SessionRegistry sessionRegistry;
    private final UserAccountRepository userAccountRepository;
    private final AnonymousIdentityRepository identityRepository;

    public AuthService(SmsCodeService smsCodeService,
                       RegistrationLimitService registrationLimitService,
                       RiskSignalService riskSignalService,
                       AnonymousAliasGenerator aliasGenerator,
                       CryptoService cryptoService,
                       JwtService jwtService,
                       CurrentUserService currentUserService,
                       SessionRegistry sessionRegistry,
                       UserAccountRepository userAccountRepository,
                       AnonymousIdentityRepository identityRepository) {
        this.smsCodeService = smsCodeService;
        this.registrationLimitService = registrationLimitService;
        this.riskSignalService = riskSignalService;
        this.aliasGenerator = aliasGenerator;
        this.cryptoService = cryptoService;
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
        this.sessionRegistry = sessionRegistry;
        this.userAccountRepository = userAccountRepository;
        this.identityRepository = identityRepository;
    }

    @Transactional
    public LoginResult login(LoginRequest request, String ip) {
        String phone = request.phone().trim();
        String phoneHash = cryptoService.hmacSha256Hex(phone);
        try {
            smsCodeService.verify(phone, request.code());
        } catch (BusinessException ex) {
            riskSignalService.recordInNewTransaction(
                    RiskSubjectType.USER, phoneHash, SIGNAL_LOGIN_FAILED, 5, RISK_TTL);
            throw ex;
        }

        Optional<UserAccount> existing = userAccountRepository.findByPhoneHash(phoneHash);
        UserAccount user;
        if (existing.isEmpty()) {
            if (!Boolean.TRUE.equals(request.acceptedTerms())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请先同意用户协议与隐私政策");
            }
            registrationLimitService.assertCanRegister(ip);
            user = createAccount(phone);
            int registrations = registrationLimitService.recordRegistration(ip);
            riskSignalService.record(RiskSubjectType.IP, ip, SIGNAL_REGISTRATION, 1, RISK_TTL);
            if (registrations >= 3) {
                riskSignalService.record(RiskSubjectType.IP, ip, SIGNAL_MULTI_ACCOUNT, 30, RISK_TTL);
            }
        } else {
            user = existing.get();
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被封禁，请联系客服");
        }

        String jti = jwtService.newSessionJti();
        user.setSessionJti(jti);
        userAccountRepository.save(user);
        sessionRegistry.bind(user.getId(), jti, jwtService.userTokenTtl());

        JwtService.IssuedToken token = jwtService.issueUserToken(user.getId().toString(), "USER", jti);
        return new LoginResult(token.token(), token.expiresAt(), toProfile(user));
    }

    @Transactional
    public void logout() {
        UserAccount user = currentUserService.getUser();
        user.setSessionJti(null);
        userAccountRepository.save(user);
        sessionRegistry.clear(user.getId());
    }

    public UserProfile currentProfile() {
        return toProfile(currentUserService.getUser());
    }

    private UserAccount createAccount(String phone) {
        UserAccount user = new UserAccount();
        user.setPhoneCipher(cryptoService.encrypt(phone));
        user.setPhoneHash(cryptoService.hmacSha256Hex(phone));
        user.setRealnameStatus(RealnameStatus.UNVERIFIED);
        user.setStatus(UserStatus.ACTIVE);
        user.setContributionScore(0);
        userAccountRepository.save(user);

        AnonymousIdentity identity = new AnonymousIdentity();
        identity.setUserId(user.getId());
        identity.setAlias(aliasGenerator.generate());
        identity.setStatus(IdentityStatus.ACTIVE);
        identityRepository.save(identity);
        return user;
    }

    private UserProfile toProfile(UserAccount user) {
        IdentityInfo identity = identityRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtAsc(user.getId(), IdentityStatus.ACTIVE)
                .map(value -> new IdentityInfo(value.getAlias(), value.getStatus()))
                .orElse(null);
        String userState = user.getRealnameStatus() == RealnameStatus.VERIFIED
                ? "REALNAME_VERIFIED"
                : "REGISTERED";
        return new UserProfile(
                user.getId(),
                cryptoService.maskedPhone(user.getPhoneCipher()),
                user.getRealnameStatus(),
                userState,
                user.getContributionScore(),
                user.getStatus(),
                identity,
                user.getCreatedAt()
        );
    }
}
