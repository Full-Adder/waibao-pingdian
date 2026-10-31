package com.waibao.pingdian.provider;

import java.util.Map;

/**
 * 短信供应商：返回验证码（业务侧落库/校验），真实实现由配置切换。
 */
public interface SmsProvider {

    String send(String phone, String template, Map<String, String> params);
}
