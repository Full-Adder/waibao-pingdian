package com.waibao.pingdian.account.service;

import com.waibao.pingdian.account.repo.AnonymousIdentityRepository;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

/**
 * 匿名身份别名生成：`匿名·XXXX`，4 位大写字母或数字，全局唯一。
 * 过滤含公司名、人名、联系方式、地域指向的组合；用户不可自定义。
 */
@Service
public class AnonymousAliasGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int SUFFIX_LENGTH = 4;
    private static final int MAX_ATTEMPTS = 20;
    private static final String PREFIX = "匿名·";

    /**
     * 仅由 A-Z0-9 组成，因此用拼音缩写/英文缩写过滤公司后缀、地域、联系方式与常见姓氏。
     */
    private static final List<String> BANNED_TOKENS = List.of(
            // 公司后缀与组织形式
            "GS", "CO", "CORP", "INC", "LTD", "LLC", "GROUP", "HR", "IT", "VIP",
            "TECH", "SOFT", "NET", "INFO", "HRM",
            // 地域缩写
            "BJ", "SH", "SZ", "HZ", "GZ", "CD", "WH", "XA", "NJ", "CQ", "TJ",
            "SY", "CS", "XM", "QD", "DL", "JN", "FZ", "HF", "ZZ", "YN", "GX",
            "BJSH", "SZHZ", "HZCD",
            // 联系方式 / 网络
            "QQ", "WX", "VX", "TEL", "MOB", "PHONE", "MAIL", "EMAIL", "HTTP", "WWW",
            "ADDR", "LOC", "MAP", "GPS",
            // 常见姓氏拼音缩写
            "LI", "WANG", "ZHANG", "CHEN", "LIU", "YANG", "ZHAO", "HUANG", "ZHOU",
            // 系统保留词
            "ADMIN", "TEST", "NULL", "TRUE", "USER", "NAME"
    );

    private final SecureRandom random = new SecureRandom();
    private final AnonymousIdentityRepository identityRepository;

    public AnonymousAliasGenerator(AnonymousIdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String suffix = randomSuffix();
            if (containsBannedToken(suffix)) {
                continue;
            }
            String alias = PREFIX + suffix;
            if (!identityRepository.existsByAlias(alias)) {
                return alias;
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "匿名身份生成失败，请重试");
    }

    private String randomSuffix() {
        StringBuilder builder = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private boolean containsBannedToken(String suffix) {
        for (String token : BANNED_TOKENS) {
            if (suffix.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
