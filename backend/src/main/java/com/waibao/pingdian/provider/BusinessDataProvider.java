package com.waibao.pingdian.provider;

import java.util.List;

/**
 * 工商数据供应商：按城市 + 行业分页拉取。
 */
public interface BusinessDataProvider {

    CompanyPage search(String city, String industry, int page, int size);

    record CompanyData(
            String uscc,
            String name,
            String shortName,
            String city,
            String industry,
            String companyType,
            String source
    ) {
    }

    record CompanyPage(List<CompanyData> items, long total, int page, int size) {
    }
}
