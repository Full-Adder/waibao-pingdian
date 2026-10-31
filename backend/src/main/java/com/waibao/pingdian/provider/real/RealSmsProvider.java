package com.waibao.pingdian.provider.real;

import com.waibao.pingdian.provider.ProviderException;
import com.waibao.pingdian.provider.SmsProvider;

import java.util.Map;

/**
 * 真实短信供应商占位：providers.mode=real 时启用，接入后替换实现即可，业务代码无需改动。
 */
public class RealSmsProvider implements SmsProvider {

    @Override
    public String send(String phone, String template, Map<String, String> params) {
        throw new ProviderException("真实短信供应商尚未接入");
    }
}
