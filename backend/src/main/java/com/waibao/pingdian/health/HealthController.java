package com.waibao.pingdian.health;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.redis.RedisSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 健康检查：DB 与 Redis 均正常返回 200，任一异常返回 503。
 * 响应只暴露 UP/DOWN 状态，不返回连接串、密码等敏感信息。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final RedisSupport redisSupport;

    public HealthController(DataSource dataSource, RedisSupport redisSupport) {
        this.dataSource = dataSource;
        this.redisSupport = redisSupport;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<HealthStatus>> health() {
        boolean dbUp = checkDatabase();
        boolean redisUp = redisSupport.ping();
        HealthStatus status = new HealthStatus(
                dbUp && redisUp ? "UP" : "DEGRADED",
                dbUp ? "UP" : "DOWN",
                redisUp ? "UP" : "DOWN");
        if (dbUp && redisUp) {
            return ResponseEntity.ok(ApiResponse.ok(status));
        }
        String message = !dbUp && !redisUp
                ? "数据库与 Redis 不可用"
                : (!dbUp ? "数据库不可用" : "Redis 不可用");
        return ResponseEntity.status(503).body(ApiResponse.fail(ErrorCode.SERVICE_UNAVAILABLE, message, status));
    }

    private boolean checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception ex) {
            log.warn("健康检查数据库连接失败：{}", ex.getClass().getSimpleName());
            return false;
        }
    }

    public record HealthStatus(String status, String database, String redis) {
    }
}
