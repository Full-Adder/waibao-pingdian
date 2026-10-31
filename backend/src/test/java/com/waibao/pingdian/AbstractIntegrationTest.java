package com.waibao.pingdian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.account.service.RegistrationLimitService;
import com.waibao.pingdian.account.service.SmsCodeService;
import com.waibao.pingdian.common.redis.RedisSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：本机原生 PostgreSQL（默认 PostgreSQL 18），不依赖 Docker。
 * 每次 mvn test 自动创建独立测试库 waibao_test_&lt;timestamp&gt;，JVM 退出时删除；
 * Flyway 自动迁移。若已有外部测试库，可设置 TEST_DB_URL 直接复用。
 *
 * Redis 使用本机 127.0.0.1:6379 的 DB 15，并按本次运行随机前缀隔离，避免污染开发库。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    private static final String DATA_KEY = "0123456789abcdef0123456789abcdef";
    private static final String HMAC_KEY = "integration-test-hmac-key-32-bytes-min";
    private static final String JWT_SECRET = "integration-test-jwt-secret-32-bytes-minimum";
    private static final String STORAGE_MASTER_KEY = "MTIzNDU2Nzg5MGFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Path STORAGE_ROOT = createStorageRoot();
    private static final String TEST_REDIS_PREFIX =
            "waibao:test:" + Long.toHexString(System.currentTimeMillis()) + ":";

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger();
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    private static final String EXTERNAL_TEST_DB_URL = env("TEST_DB_URL", "");
    private static final String TEST_DB_NAME = "waibao_test_" + Long.toHexString(System.currentTimeMillis());
    private static final String TEST_DB_URL;

    static {
        if (EXTERNAL_TEST_DB_URL.isBlank()) {
            createTestDatabase();
            TEST_DB_URL = "jdbc:postgresql://" + dbHost() + ":" + dbPort() + "/" + TEST_DB_NAME;
            Runtime.getRuntime().addShutdownHook(new Thread(AbstractIntegrationTest::dropTestDatabase));
        } else {
            TEST_DB_URL = EXTERNAL_TEST_DB_URL;
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DB_URL);
        registry.add("spring.datasource.username", AbstractIntegrationTest::dbUser);
        registry.add("spring.datasource.password", AbstractIntegrationTest::dbPassword);
        registry.add("auth.jwt.secret", () -> JWT_SECRET);
        registry.add("crypto.data-key", () -> DATA_KEY);
        registry.add("crypto.hmac-key", () -> HMAC_KEY);
        registry.add("storage.root", () -> STORAGE_ROOT.toString());
        registry.add("storage.master-key", () -> STORAGE_MASTER_KEY);
        registry.add("admin.seed.account", () -> "");
        registry.add("admin.seed.password", () -> "");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.data.redis.database", () -> "15");
        registry.add("app.redis.key-prefix", () -> TEST_REDIS_PREFIX);
        // 集成测试固定同步路径，避免依赖 RabbitMQ；同时关闭监听器与自动声明以减少噪音。
        registry.add("app.mq.async", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        // 集成测试不启动 XXL-Job 执行器，也不连本地调度中心。
        registry.add("xxl.job.enabled", () -> "false");
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String dbHost() {
        return env("TEST_DB_HOST", env("DB_HOST", "localhost"));
    }

    private static String dbPort() {
        return env("TEST_DB_PORT", env("DB_PORT", "5432"));
    }

    private static String dbUser() {
        return env("TEST_DB_USER", env("DB_USER", System.getProperty("user.name", "waibao")));
    }

    private static String dbPassword() {
        return env("TEST_DB_PASSWORD", env("DB_PASSWORD", ""));
    }

    private static String adminDbUrl() {
        return "jdbc:postgresql://" + dbHost() + ":" + dbPort() + "/postgres";
    }

    private static void createTestDatabase() {
        try (Connection connection = DriverManager.getConnection(adminDbUrl(), dbUser(), dbPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + TEST_DB_NAME);
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "无法在本机 PostgreSQL 创建测试库。请确认服务已启动，或设置 TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD。"
                            + " 当前连接：" + adminDbUrl(), ex);
        }
    }

    private static void dropTestDatabase() {
        try (Connection connection = DriverManager.getConnection(adminDbUrl(), dbUser(), dbPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + TEST_DB_NAME + " WITH (FORCE)");
        } catch (Exception ignored) {
            // JVM 退出阶段清理失败不影响测试结果。
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("waibao-test-storage");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    protected static Path storageRoot() {
        return STORAGE_ROOT;
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected SmsCodeService smsCodeService;

    @Autowired
    protected RegistrationLimitService registrationLimitService;

    @Autowired
    protected RedisSupport redisSupport;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetInMemoryState() {
        // 只清理本次测试运行的随机前缀，不影响开发库 DB 0 与其他并发测试。
        redisSupport.deleteByPattern("*");
        smsCodeService.clearAll();
        registrationLimitService.clearAll();
    }

    protected static String uniquePhone() {
        return "139" + String.format("%08d", PHONE_SEQ.incrementAndGet());
    }

    protected static String uniqueIp() {
        return "203.0.113." + (IP_SEQ.incrementAndGet() % 250 + 1);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected void assertSuccess(JsonNode body) {
        assertThat(body.path("code").asInt()).isZero();
    }

    /**
     * 发送验证码并完成注册/登录，返回 LoginResult 的 data 节点。
     */
    protected JsonNode registerAndLogin(String phone, String ip) throws Exception {
        mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "scene", "LOGIN"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "code", "123456", "acceptedTerms", true)))
                        .header("X-Forwarded-For", ip))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        JsonNode body = body(login);
        assertSuccess(body);
        return body.path("data");
    }

    /**
     * 创建指定角色的管理员并登录，返回 admin token。
     */
    protected String adminToken(String role) throws Exception {
        String account = "adm_" + uniquePhone();
        String password = "adminPass123";
        jdbcTemplate.update("""
                        INSERT INTO admin_user (account, password_hash, role, status)
                        VALUES (?, ?, ?, 'ACTIVE')
                        """,
                account, passwordEncoder.encode(password), role);
        MvcResult login = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("account", account, "password", password))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        JsonNode body = body(login);
        assertSuccess(body);
        return body.path("data").path("token").asText();
    }

    protected MvcResult getWithToken(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
    }
}
