package io.polaris.gateway.ratelimit;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

class InMemoryGatewayRateLimiter implements GatewayRateLimiter {
    private final GatewayRateLimitProperties properties;
    private final GatewayRateLimitKeyResolver keyResolver;
    private final Clock clock;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    InMemoryGatewayRateLimiter(
            GatewayRateLimitProperties properties,
            GatewayRateLimitKeyResolver keyResolver,
            Clock clock
    ) {
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> tryAcquire(ServerWebExchange exchange) {
        if (properties.requestsPerWindow() <= 0) {
            return Mono.just(true);
        }

        long currentWindow = currentWindow();
        return keyResolver.resolve(exchange)
                .map(key -> allow(key, currentWindow));
    }

    private boolean allow(String key, long currentWindow) {
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.window() != currentWindow) {
                return new WindowCounter(currentWindow, new AtomicInteger());
            }
            return existing;
        });
        return counter.requests().incrementAndGet() <= properties.requestsPerWindow();
    }

    private long currentWindow() {
        long windowSeconds = Math.max(1, properties.window().toSeconds());
        return clock.instant().getEpochSecond() / windowSeconds;
    }

    private record WindowCounter(long window, AtomicInteger requests) {
    }
}
