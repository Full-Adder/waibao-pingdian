package com.waibao.pingdian.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCH_NAME_USCC = "919990010000000001";
    private static final String SEARCH_ALIAS_USCC = "919990010000000002";
    private static final String REPORT_USCC = "919990020000000001";
    private static final String MERGE_SOURCE_USCC = "919990030000000001";
    private static final String MERGE_TARGET_USCC = "919990030000000002";
    private static final String DETAIL_USCC = "919990040000000001";

    @Test
    void importRunsTwiceIdempotentlyByUscc() throws Exception {
        String admin = adminToken("CONTENT_ADMIN");
        JsonNode first = triggerImportAndWait(admin, "成都", "IT人力外包");
        assertThat(first.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(first.path("total").asInt()).isEqualTo(417);
        assertThat(first.path("upserted").asInt()).isEqualTo(417);
        assertThat(first.path("failed").asInt()).isZero();

        int firstCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company WHERE city = ? AND industry = ?",
                Integer.class, "成都", "IT人力外包");
        assertThat(firstCount).isEqualTo(417);

        JsonNode second = triggerImportAndWait(admin, "成都", "IT人力外包");
        assertThat(second.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(second.path("upserted").asInt()).isEqualTo(417);

        int secondCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company WHERE city = ? AND industry = ?",
                Integer.class, "成都", "IT人力外包");
        assertThat(secondCount).isEqualTo(417);

        int jobCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM import_job WHERE city = ? AND industry = ?",
                Integer.class, "成都", "IT人力外包");
        assertThat(jobCount).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'IMPORT_JOB_CREATE'",
                Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void searchMatchesNameShortNameAliasAndIncludesZeroReviewCompanies() throws Exception {
        insertCompany(SEARCH_NAME_USCC, "北京极光软件开发有限公司", "极光软件", "北京", "IT项目外包", "BUSINESS_API");
        insertCompany(SEARCH_ALIAS_USCC, "上海远航数据科技有限公司", "远航数据", "上海", "IT项目外包", "BUSINESS_API");
        jdbcTemplate.update("INSERT INTO company_alias (uscc, alias_name, alias_type) VALUES (?, ?, 'SHORT_NAME')",
                SEARCH_ALIAS_USCC, "远航科技");

        MvcResult byName = mockMvc.perform(get("/api/companies/search").param("keyword", "北京极光"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode nameBody = body(byName);
        assertSuccess(nameBody);
        JsonNode nameItems = nameBody.path("data").path("items");
        assertThat(nameItems.size()).isGreaterThanOrEqualTo(1);
        JsonNode first = nameItems.get(0);
        assertThat(first.path("uscc").asText()).isEqualTo(SEARCH_NAME_USCC);
        assertThat(first.path("reviewCount").asInt()).isZero();
        assertThat(first.path("hasReviews").asBoolean()).isFalse();
        assertThat(first.path("scoreVisible").asBoolean()).isFalse();
        assertThat(first.has("avgScore")).isFalse();

        MvcResult byShort = mockMvc.perform(get("/api/companies/search").param("keyword", "极光软件"))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(byShort).path("data").path("items").get(0).path("uscc").asText())
                .isEqualTo(SEARCH_NAME_USCC);

        MvcResult byAlias = mockMvc.perform(get("/api/companies/search").param("keyword", "远航科技"))
                .andExpect(status().isOk()).andReturn();
        JsonNode aliasItems = body(byAlias).path("data").path("items");
        assertThat(aliasItems.size()).isGreaterThanOrEqualTo(1);
        assertThat(aliasItems.findValuesAsText("uscc")).contains(SEARCH_ALIAS_USCC);

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM search_log WHERE keyword = ?", Integer.class, "远航科技");
        assertThat(logged).isGreaterThanOrEqualTo(1);
    }

    @Test
    void searchUsesTrigramIndexOnLargeDataset() throws Exception {
        seedPerformanceCompanies();
        jdbcTemplate.execute("ANALYZE company");

        List<String> plan = jdbcTemplate.execute((Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery(
                        "EXPLAIN SELECT uscc FROM company WHERE name ILIKE '%性能压测公司00%'")) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                } finally {
                    // 还池前恢复会话参数，避免后续请求被强制索引计划污染。
                    statement.execute("SET enable_seqscan = on");
                }
                return lines;
            }
        });
        String planText = String.join("\n", plan);
        System.out.println("EXPLAIN plan:\n" + planText);
        assertThat(planText).contains("idx_company_name_trgm");

        long[] durations = new long[100];
        for (int i = 0; i < 110; i++) {
            long start = System.nanoTime();
            MvcResult result = mockMvc.perform(get("/api/companies/search")
                            .param("keyword", "性能压测公司00")
                            .param("page", "1")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andReturn();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            assertThat(body(result).path("data").path("items").size()).isEqualTo(20);
            if (i >= 10) {
                durations[i - 10] = elapsed;
            }
        }
        Arrays.sort(durations);
        long p95 = durations[(int) Math.ceil(durations.length * 0.95) - 1];
        System.out.println("search P95=" + p95 + "ms, median="
                + durations[durations.length / 2] + "ms");
        assertThat(p95).isLessThan(300);
    }

    @Test
    void userReportMatchesExistingOrOnlyCreatesCandidate() throws Exception {
        insertCompany(REPORT_USCC, "北京测试科技有限公司", "测试科技", "北京", "IT项目外包", "BUSINESS_API");
        String token = registerAndLogin(uniquePhone(), uniqueIp()).path("token").asText();

        int before = jdbcTemplate.queryForObject("SELECT count(*) FROM company", Integer.class);
        MvcResult matched = mockMvc.perform(post("/api/companies/report")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "北京 测试 科技 有限公司（集团）",
                                "city", "北京",
                                "industry", "IT项目外包"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode matchedBody = body(matched);
        assertSuccess(matchedBody);
        assertThat(matchedBody.path("data").path("matched").asBoolean()).isTrue();
        assertThat(matchedBody.path("data").path("pending").asBoolean()).isFalse();
        assertThat(matchedBody.path("data").path("company").path("uscc").asText()).isEqualTo(REPORT_USCC);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company", Integer.class))
                .isEqualTo(before);

        MvcResult pending = mockMvc.perform(post("/api/companies/report")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "全新未注册外包有限公司",
                                "city", "北京",
                                "industry", "IT项目外包"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pendingBody = body(pending);
        assertSuccess(pendingBody);
        assertThat(pendingBody.path("data").path("matched").asBoolean()).isFalse();
        assertThat(pendingBody.path("data").path("pending").asBoolean()).isTrue();
        String candidateId = pendingBody.path("data").path("candidateId").asText();
        assertThat(candidateId).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company WHERE name = ?", Integer.class,
                "全新未注册外包有限公司")).isZero();

        MvcResult duplicate = mockMvc.perform(post("/api/companies/report")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "全新未注册 外包有限公司",
                                "city", "北京",
                                "industry", "IT项目外包"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(duplicate).path("data").path("candidateId").asText()).isEqualTo(candidateId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company_candidate WHERE name = ?", Integer.class,
                "全新未注册外包有限公司")).isEqualTo(1);
    }

    @Test
    void mergeMigratesRelationsWritesRedirectAndExcludesSitemap() throws Exception {
        insertCompany(MERGE_SOURCE_USCC, "北京重复档案科技有限公司", "重复档案", "北京", "IT项目外包", "BUSINESS_API");
        insertCompany(MERGE_TARGET_USCC, "北京主档案科技有限公司", "主档案", "北京", "IT项目外包", "BUSINESS_API");
        jdbcTemplate.update("INSERT INTO company_alias (uscc, alias_name) VALUES (?, ?)",
                MERGE_SOURCE_USCC, "源公司别名");

        JsonNode user = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        UUID identityId = jdbcTemplate.queryForObject(
                "SELECT id FROM anonymous_identity WHERE user_id = ?", UUID.class, userId);
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO review (id, uscc, user_id, identity_id, total_score, content, status, published_at)
                        VALUES (?, ?, ?, ?, 4, '测试合并评价正文，长度足够。', 'PUBLISHED', now())
                        """, reviewId, MERGE_SOURCE_USCC, userId, identityId);
        jdbcTemplate.update("""
                        INSERT INTO user_verification
                            (user_id, proof_type, company_uscc, period_start, period_end, status)
                        VALUES (?, 'LABOR_CONTRACT', ?, DATE '2023-01-01', DATE '2023-12-31', 'APPROVED')
                        """, userId, MERGE_SOURCE_USCC);

        String admin = adminToken("CONTENT_ADMIN");
        MvcResult merge = mockMvc.perform(post("/api/admin/companies/{source}/merge-into/{target}",
                                MERGE_SOURCE_USCC, MERGE_TARGET_USCC)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "重复档案合并"))))
                .andExpect(status().isOk())
                .andReturn();
        assertSuccess(body(merge));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review WHERE uscc = ?", Integer.class, MERGE_TARGET_USCC)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT uscc FROM review WHERE id = ?", String.class, reviewId)).isEqualTo(MERGE_TARGET_USCC);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_uscc FROM user_verification WHERE user_id = ?", String.class, userId))
                .isEqualTo(MERGE_TARGET_USCC);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company_alias WHERE uscc = ?", Integer.class, MERGE_TARGET_USCC))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM company WHERE uscc = ?", String.class, MERGE_SOURCE_USCC))
                .isEqualTo("MERGED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT new_uscc FROM uscc_redirect WHERE old_uscc = ?", String.class, MERGE_SOURCE_USCC))
                .isEqualTo(MERGE_TARGET_USCC);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_count FROM company WHERE uscc = ?", Integer.class, MERGE_TARGET_USCC))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avg_score FROM company WHERE uscc = ?", BigDecimal.class, MERGE_TARGET_USCC))
                .isEqualByComparingTo(new BigDecimal("4.0"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT score_visible FROM company WHERE uscc = ?", Boolean.class, MERGE_TARGET_USCC))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_count FROM company WHERE uscc = ?", Integer.class, MERGE_SOURCE_USCC))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avg_score FROM company WHERE uscc = ?", BigDecimal.class, MERGE_SOURCE_USCC))
                .isNull();

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT before_hash, after_hash FROM admin_audit_log WHERE action = 'COMPANY_MERGE' AND target_id = ?",
                MERGE_SOURCE_USCC);
        assertThat((String) audit.get("before_hash")).hasSize(64);
        assertThat((String) audit.get("after_hash")).hasSize(64);

        MvcResult detail = mockMvc.perform(get("/api/companies/{uscc}", MERGE_SOURCE_USCC))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(detail).path("data").path("redirectTo").asText()).isEqualTo(MERGE_TARGET_USCC);

        MvcResult sitemap = mockMvc.perform(get("/api/companies/sitemap")
                        .param("city", "北京").param("industry", "IT项目外包"))
                .andExpect(status().isOk()).andReturn();
        List<String> sitemapUsccs = body(sitemap).path("data").findValuesAsText("uscc");
        assertThat(sitemapUsccs).contains(MERGE_TARGET_USCC).doesNotContain(MERGE_SOURCE_USCC);

        mockMvc.perform(post("/api/admin/companies/{source}/merge-into/{target}",
                        MERGE_TARGET_USCC, MERGE_TARGET_USCC)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "自合并"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void companyDetailExposesSourceAliasesAndScoreGate() throws Exception {
        insertCompany(DETAIL_USCC, "成都人工补录外包有限公司", "人工补录", "成都", "IT项目外包", "USER_REPORT");
        jdbcTemplate.update("INSERT INTO company_alias (uscc, alias_name) VALUES (?, ?)",
                DETAIL_USCC, "补录旧名");

        MvcResult detail = mockMvc.perform(get("/api/companies/{uscc}", DETAIL_USCC))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = body(detail).path("data");
        assertThat(data.path("source").asText()).isEqualTo("USER_REPORT");
        assertThat(data.path("sourceLabel").asText()).isEqualTo("用户上报");
        assertThat(data.path("aliases").get(0).asText()).isEqualTo("补录旧名");
        assertThat(data.path("reviewCount").asInt()).isZero();
        assertThat(data.path("scoreVisible").asBoolean()).isFalse();
        assertThat(data.path("scoreNote").asText()).isEqualTo("评价不足，暂不评分");
    }

    // AC-21/R8：管理端公司详情可读，公开详情统计已通过的企业回应数
    @Test
    void adminCompanyDetailAndApprovedResponseCount() throws Exception {
        insertCompany(DETAIL_USCC, "成都人工补录外包有限公司", "人工补录", "成都", "IT项目外包", "USER_REPORT");

        JsonNode user = registerAndLogin(uniquePhone(), uniqueIp());
        UUID userId = UUID.fromString(user.path("user").path("id").asText());
        UUID identityId = jdbcTemplate.queryForObject(
                "SELECT id FROM anonymous_identity WHERE user_id = ?", UUID.class, userId);
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO review (id, uscc, user_id, identity_id, total_score, content, status, published_at)
                        VALUES (?, ?, ?, ?, 4, '公司回应测试评价正文，长度足够。', 'PUBLISHED', now())
                        """, reviewId, DETAIL_USCC, userId, identityId);
        jdbcTemplate.update("""
                        INSERT INTO enterprise_response (review_id, uscc, content, status)
                        VALUES (?, ?, '感谢反馈，我们会持续改进。', 'APPROVED')
                        """, reviewId, DETAIL_USCC);

        JsonNode publicDetail = body(mockMvc.perform(get("/api/companies/{uscc}", DETAIL_USCC))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(publicDetail.path("responseCount").asInt()).isEqualTo(1);

        String admin = adminToken("CONTENT_ADMIN");
        JsonNode adminDetail = body(getWithToken("/api/admin/companies/" + DETAIL_USCC, admin)).path("data");
        assertThat(adminDetail.path("uscc").asText()).isEqualTo(DETAIL_USCC);
        assertThat(adminDetail.path("responseCount").asInt()).isEqualTo(1);
    }

    private JsonNode triggerImportAndWait(String adminToken, String city, String industry) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/admin/imports")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "city", city, "industry", industry, "triggerType", "MANUAL"))))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode createBody = body(created);
        assertSuccess(createBody);
        String jobId = createBody.path("data").path("id").asText();

        for (int i = 0; i < 300; i++) {
            MvcResult detail = getWithToken("/api/admin/imports/" + jobId, adminToken);
            JsonNode data = body(detail).path("data");
            String status = data.path("status").asText();
            if (!"RUNNING".equals(status)) {
                return data;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("import job did not finish in time: " + jobId);
    }

    private void insertCompany(String uscc, String name, String shortName, String city, String industry,
                               String source) {
        jdbcTemplate.update("""
                        INSERT INTO company (uscc, name, short_name, city, industry, company_type,
                                             source, review_count, avg_score, score_visible, status)
                        VALUES (?, ?, ?, ?, ?, '有限责任公司', ?, 0, NULL, false, 'ACTIVE')
                        ON CONFLICT (uscc) DO UPDATE SET
                            name = EXCLUDED.name, short_name = EXCLUDED.short_name,
                            city = EXCLUDED.city, industry = EXCLUDED.industry,
                            source = EXCLUDED.source
                        """, uscc, name, shortName, city, industry, source);
    }

    private void seedPerformanceCompanies() {
        List<Object[]> rows = new ArrayList<>(5000);
        for (int i = 0; i < 5000; i++) {
            String uscc = String.format("91995%013d", i);
            String name = "性能压测公司" + String.format("%04d", i) + "有限公司";
            rows.add(new Object[]{uscc, name, "性能压测" + String.format("%04d", i),
                    "杭州", "IT人力外包", "BUSINESS_API"});
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO company (uscc, name, short_name, city, industry, company_type,
                                             source, review_count, avg_score, score_visible, status)
                        VALUES (?, ?, ?, ?, ?, '有限责任公司', ?, 0, NULL, false, 'ACTIVE')
                        ON CONFLICT (uscc) DO UPDATE SET
                            name = EXCLUDED.name, short_name = EXCLUDED.short_name
                        """, rows);
    }
}
