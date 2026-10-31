package com.waibao.pingdian.provider.real;

import com.waibao.pingdian.provider.ProviderException;
import com.waibao.pingdian.provider.RealnameProvider;

/**
 * 真实实名核验供应商占位。
 */
public class RealRealnameProvider implements RealnameProvider {

    @Override
    public RealnameResult verify(String name, String idNumber) {
        throw new ProviderException("真实实名核验供应商尚未接入");
    }
}
