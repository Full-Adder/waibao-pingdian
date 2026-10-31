package com.waibao.pingdian.track.service;

import com.waibao.pingdian.common.redis.RedisSupport;
import com.waibao.pingdian.track.web.dto.TrackVisitRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 访问埋点写入。仅记录匿名 visitorId、路径、UA 与时间；不记录 IP、手机号或证件等明文。
 * 同一 visitorId + path 在 1 分钟内重复上报时不再重复写入：
 * Redis {@code visit:dedupe:{visitorId}:{sha256(path)}} SET NX EX 60，
 * Redis 故障时回退数据库 NOT EXISTS 判断，保证埋点不因缓存不可用而失败。
 */
@Service
public class VisitTrackingService {

    private static final Logger log = LoggerFactory.getLogger(VisitTrackingService.class);
    private static final Duration DEDUPE_TTL = Duration.ofSeconds(60);
    private static final String DEDUPE_PREFIX = "visit:dedupe:";

    private final JdbcTemplate jdbcTemplate;
    private final RedisSupport redis;

    public VisitTrackingService(JdbcTemplate jdbcTemplate, RedisSupport redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
    }

    public void record(TrackVisitRequest request) {
        String visitorId = request.visitorId().trim();
        String path = request.path().trim();
        String userAgent = blankToNull(request.userAgent());
        if (!firstVisitWithinWindow(visitorId, path)) {
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO visit_event (visitor_id, path, user_agent)
                        SELECT ?, ?, ?
                        WHERE NOT EXISTS (
                            SELECT 1 FROM visit_event
                            WHERE visitor_id = ? AND path = ?
                              AND created_at >= now() - interval '1 minute'
                        )
                        """,
                visitorId, path, userAgent, visitorId, path);
    }

    private boolean firstVisitWithinWindow(String visitorId, String path) {
        String key = redis.key(DEDUPE_PREFIX + visitorId + ":" + sha256(path));
        try {
            return redis.putIfAbsent(key, "1", DEDUPE_TTL);
        } catch (Exception ex) {
            log.warn("访问去重写入 Redis 失败，回退数据库判断：{}", ex.getClass().getSimpleName());
            return true;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
