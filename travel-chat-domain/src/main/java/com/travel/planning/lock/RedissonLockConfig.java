package com.travel.planning.lock;

import com.travel.common.lock.LockPort;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * X-7a：Redisson 分布式锁装配（{@code travel.lock.redisson.enabled=false} 默认关=E-33 现状等价：
 * 两 Bean 均不装配，LockPort 注入落至 NoOpLockPort）。
 *
 * <p>r3 裁定：独立 {@code Redisson.create(config)} core 实例直连——禁 redisson-spring-boot-starter
 * 入模块（starter 会替换 Boot 的 Redis 连接工厂，被动回归 22 处 StringRedisTemplate 触点）；
 * config 读 {@code spring.data.redis.host/port}，无密码不设 password 字段（既有 Redis 语义：
 * 避免发送 AUTH 空密码）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "travel.lock.redisson", name = "enabled", havingValue = "true")
public class RedissonLockConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient travelRedissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return Redisson.create(buildConfig(host, port));
    }

    /** @Primary：启用时接管 LockPort 注入（NoOpLockPort 仍存在作兜底）。 */
    @Bean
    @Primary
    public LockPort redissonLockPort(RedissonClient travelRedissonClient) {
        return new RedissonLockPort(travelRedissonClient);
    }

    /** 包级可见供单测参数断言（纯 Config 构建，不连真实 Redis）。 */
    static Config buildConfig(String host, int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return config;
    }
}
