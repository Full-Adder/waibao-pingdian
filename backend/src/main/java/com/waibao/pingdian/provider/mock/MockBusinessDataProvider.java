package com.waibao.pingdian.provider.mock;

import com.waibao.pingdian.config.ProvidersProperties;
import com.waibao.pingdian.provider.BusinessDataProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock 工商数据：6 城市 × 2 行业，按 USCC 确定性生成，同一参数多次调用结果稳定。
 */
public class MockBusinessDataProvider implements BusinessDataProvider {

    private static final List<String> CITIES = List.of("北京", "上海", "深圳", "杭州", "广州", "成都");
    private static final List<String> INDUSTRIES = List.of("IT人力外包", "IT项目外包");

    private final ProvidersProperties properties;

    public MockBusinessDataProvider(ProvidersProperties properties) {
        this.properties = properties;
    }

    @Override
    public CompanyPage search(String city, String industry, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(200, size));
        int perGroup = Math.max(1, properties.getMock().getBusinessData().getPerGroupCount());

        List<GroupRef> matched = new ArrayList<>();
        for (int cityIndex = 0; cityIndex < CITIES.size(); cityIndex++) {
            if (city != null && !city.isBlank() && !CITIES.get(cityIndex).equals(city.trim())) {
                continue;
            }
            for (int industryIndex = 0; industryIndex < INDUSTRIES.size(); industryIndex++) {
                if (industry != null && !industry.isBlank() && !INDUSTRIES.get(industryIndex).equals(industry.trim())) {
                    continue;
                }
                matched.add(new GroupRef(cityIndex, industryIndex));
            }
        }

        long total = (long) matched.size() * perGroup;
        long from = (long) (safePage - 1) * safeSize;
        if (from >= total) {
            return new CompanyPage(List.of(), total, safePage, safeSize);
        }
        long to = Math.min(total, from + safeSize);
        List<CompanyData> items = new ArrayList<>((int) (to - from));
        for (long index = from; index < to; index++) {
            GroupRef group = matched.get((int) (index / perGroup));
            int sequence = (int) (index % perGroup) + 1;
            items.add(build(group, sequence));
        }
        return new CompanyPage(List.copyOf(items), total, safePage, safeSize);
    }

    private CompanyData build(GroupRef group, int sequence) {
        String city = CITIES.get(group.cityIndex());
        String industry = INDUSTRIES.get(group.industryIndex());
        String groupCode = String.format("%06d", (group.cityIndex() + 1) * 100 + (group.industryIndex() + 1));
        String uscc = "91" + groupCode + String.format("%010d", sequence);
        String name = city + industry + String.format("%04d", sequence) + "科技有限公司";
        String shortName = city + industry + String.format("%04d", sequence);
        return new CompanyData(uscc, name, shortName, city, industry, "有限责任公司", "BUSINESS_API");
    }

    private record GroupRef(int cityIndex, int industryIndex) {
    }
}
