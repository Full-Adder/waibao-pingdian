package com.waibao.pingdian.health;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.redis.RedisSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 不可用时的健康检查：DB 正常但 Redis PING 失败 → 503，字段标明 redis=DOWN。
 * 用 mock 构造，不依赖真实 Redis 端口，避免影响其他测试。
 */
class HealthControllerTest {

    @Test
    void redisDownReturns503WithDownStatus() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        RedisSupport redisSupport = mock(RedisSupport.class);
        when(redisSupport.ping()).thenReturn(false);

        HealthController controller = new HealthController(dataSource, redisSupport);
        ResponseEntity<ApiResponse<HealthController.HealthStatus>> response = controller.health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        ApiResponse<HealthController.HealthStatus> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data().status()).isEqualTo("DEGRADED");
        assertThat(body.data().database()).isEqualTo("UP");
        assertThat(body.data().redis()).isEqualTo("DOWN");
        assertThat(body.message()).contains("Redis");
    }

    @Test
    void databaseDownReturns503WithDownStatus() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("unavailable"));

        RedisSupport redisSupport = mock(RedisSupport.class);
        when(redisSupport.ping()).thenReturn(true);

        HealthController controller = new HealthController(dataSource, redisSupport);
        ResponseEntity<ApiResponse<HealthController.HealthStatus>> response = controller.health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().database()).isEqualTo("DOWN");
        assertThat(response.getBody().data().redis()).isEqualTo("UP");
        assertThat(response.getBody().message()).contains("数据库");
    }
}
