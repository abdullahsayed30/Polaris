package io.polaris.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;

class RedisGatewayRateLimiter implements GatewayRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisGatewayRateLimiter.class);
    private static final String KEY_PREFIX = "polaris:gateway:rate-limit:";

    private final GatewayRateLimitProperties properties;
    private final GatewayRateLimitKeyResolver keyResolver;
    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;

    RedisGatewayRateLimiter(
            GatewayRateLimitProperties properties,
            GatewayRateLimitKeyResolver keyResolver,
            ReactiveStringRedisTemplate redis,
            Clock clock
    ) {
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> tryAcquire(ServerWebExchange exchange) {
        if (properties.requestsPerWindow() <= 0) {
            return Mono.just(true);
        }

        return keyResolver.resolve(exchange)
                .flatMap(key -> tryAcquire(key))
                .onErrorResume(error -> {
                    log.warn(
                            "gateway rate limiter failed open backend=redis errorType={} message={}",
                            error.getClass().getName(),
                            error.getMessage()
                    );
                    return Mono.just(true);
                });
    }

    private Mono<Boolean> tryAcquire(String key) {
        String redisKey = redisKey(key);
        return redis.opsForValue()
                .increment(redisKey)
                .flatMap(count -> expireIfNew(redisKey, count)
                        .thenReturn(count <= properties.requestsPerWindow()));
    }

    private Mono<Boolean> expireIfNew(String redisKey, Long count) {
        if (count == null || count != 1L) {
            return Mono.just(false);
        }
        return redis.expire(redisKey, properties.window().plusSeconds(1));
    }

    private String redisKey(String key) {
        long windowSeconds = Math.max(1, properties.window().toSeconds());
        long bucket = clock.instant().getEpochSecond() / windowSeconds;
        return KEY_PREFIX + sanitize(key) + ":" + bucket;
    }

    private String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9:._-]", "_");
    }
}
