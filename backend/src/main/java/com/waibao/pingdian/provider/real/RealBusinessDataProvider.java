package com.waibao.pingdian.provider.real;

import com.waibao.pingdian.provider.BusinessDataProvider;
import com.waibao.pingdian.provider.ProviderException;

/**
 * 真实工商数据供应商占位。
 */
public class RealBusinessDataProvider implements BusinessDataProvider {

    @Override
    public CompanyPage search(String city, String industry, int page, int size) {
        throw new ProviderException("真实工商数据供应商尚未接入");
    }
}
