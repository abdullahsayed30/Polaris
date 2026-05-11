package io.polaris.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Clock;

@Configuration
class RateLimiterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    GatewayRateLimitKeyResolver gatewayRateLimitKeyResolver(GatewayRateLimitProperties properties) {
        return new GatewayRateLimitKeyResolver(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "polaris.gateway.rate-limit", name = "backend", havingValue = "redis")
    @ConditionalOnBean(ReactiveStringRedisTemplate.class)
    GatewayRateLimiter redisGatewayRateLimiter(
            GatewayRateLimitProperties properties,
            GatewayRateLimitKeyResolver keyResolver,
            ReactiveStringRedisTemplate redis,
            Clock clock
    ) {
        return new RedisGatewayRateLimiter(properties, keyResolver, redis, clock);
    }

    @Bean
    @ConditionalOnMissingBean(GatewayRateLimiter.class)
    GatewayRateLimiter inMemoryGatewayRateLimiter(
            GatewayRateLimitProperties properties,
            GatewayRateLimitKeyResolver keyResolver,
            Clock clock
    ) {
        return new InMemoryGatewayRateLimiter(properties, keyResolver, clock);
    }
}
