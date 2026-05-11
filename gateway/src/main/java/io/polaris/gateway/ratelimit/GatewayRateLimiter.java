package io.polaris.gateway.ratelimit;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

interface GatewayRateLimiter {
    Mono<Boolean> tryAcquire(ServerWebExchange exchange);
}
