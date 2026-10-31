package com.waibao.pingdian.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志与异常信息脱敏：手机号、18 位身份证号、邮箱不得以明文进入日志。
 */
public final class SensitiveDataMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<![\\dXx])\\d{17}[\\dXx](?![\\dXx])");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+)");

    private SensitiveDataMasker() {
    }

    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = maskPhones(input);
        result = maskIdCards(result);
        result = maskEmails(result);
        return result;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 18 位身份证号掩码：保留前 6 位与后 4 位，中间 8 位以星号替代。
     */
    public static String maskIdNumber(String idNumber) {
        if (idNumber == null || idNumber.length() < 10) {
            return "***";
        }
        return idNumber.substring(0, 6) + "********" + idNumber.substring(idNumber.length() - 4);
    }

    private static String maskPhones(String input) {
        Matcher matcher = PHONE.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String phone = matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(maskPhone(phone)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String maskIdCards(String input) {
        Matcher matcher = ID_CARD.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String id = matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(maskIdNumber(id)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String maskEmails(String input) {
        Matcher matcher = EMAIL.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replaced = matcher.group(1) + "***" + matcher.group(2);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
