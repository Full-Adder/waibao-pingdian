package com.waibao.pingdian.redis;

import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.common.redis.RedisLockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisLockService：互斥抢占、仅持有者可释放、拿不到锁返回 skipped。
 */
class RedisLockServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RedisLockService lockService;

    @Test
    void tryLockIsMutuallyExclusiveAndOnlyOwnerCanUnlock() {
        String name = "test-lock-" + UUID.randomUUID();
        Duration ttl = Duration.ofSeconds(30);

        Optional<String> first = lockService.tryLock(name, ttl);
        assertThat(first).isPresent();
        assertThat(lockService.tryLock(name, ttl)).isEmpty();

        // 其他 token 无法删除锁，锁仍由首个持有者掌控。
        assertThat(lockService.unlock(name, "wrong-token")).isFalse();
        assertThat(redisSupport.find(redisSupport.key("lock:" + name)).orElse(null))
                .isEqualTo(first.get());

        assertThat(lockService.unlock(name, first.get())).isTrue();
        assertThat(lockService.tryLock(name, ttl)).isPresent();
    }

    @Test
    void runWithLockSkipsWhenLockIsHeldAndExecutesAfterRelease() {
        String name = "test-run-lock-" + UUID.randomUUID();
        Duration ttl = Duration.ofSeconds(30);
        AtomicInteger runs = new AtomicInteger();

        Optional<String> held = lockService.tryLock(name, ttl);
        assertThat(held).isPresent();

        RedisLockService.LockResult<Integer> skipped =
                lockService.runWithLock(name, ttl, runs::incrementAndGet);
        assertThat(skipped.acquired()).isFalse();
        assertThat(runs).hasValue(0);

        assertThat(lockService.unlock(name, held.get())).isTrue();
        RedisLockService.LockResult<Integer> executed = lockService.runWithLock(name, ttl, () -> {
            runs.incrementAndGet();
            return 1;
        });
        assertThat(executed.acquired()).isTrue();
        assertThat(executed.value()).isEqualTo(1);
        assertThat(redisSupport.find(redisSupport.key("lock:" + name)).orElse(null)).isNull();
    }
}
