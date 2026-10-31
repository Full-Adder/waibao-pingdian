package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.domain.RiskSignal;
import com.waibao.pingdian.account.domain.RiskSubjectType;
import com.waibao.pingdian.account.repo.RiskSignalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 风险信号记录：注册 IP、登录失败、短时多账号注册等。
 * subject_value 一律使用 IP 或手机号 HMAC（禁止写入手机号明文）。
 */
@Service
public class RiskSignalService {

    private final RiskSignalRepository riskSignalRepository;

    public RiskSignalService(RiskSignalRepository riskSignalRepository) {
        this.riskSignalRepository = riskSignalRepository;
    }

    public void record(RiskSubjectType subjectType, String subjectValue,
                       String signalType, int score, Duration ttl) {
        RiskSignal signal = new RiskSignal();
        signal.setSubjectType(subjectType);
        signal.setSubjectValue(subjectValue);
        signal.setSignalType(signalType);
        signal.setScore(score);
        signal.setHitCount(1);
        signal.setExpiresAt(ttl == null ? null : Instant.now().plus(ttl));
        riskSignalRepository.save(signal);
    }

    /**
     * 失败路径专用：独立事务写入，避免调用方回滚导致信号丢失。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(RiskSubjectType subjectType, String subjectValue,
                                       String signalType, int score, Duration ttl) {
        record(subjectType, subjectValue, signalType, score, ttl);
    }
}
