package io.polaris.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
class RateLimitingFilter implements GlobalFilter, Ordered {
    private static final byte[] RATE_LIMIT_RESPONSE = "{\"error\":\"rate_limit_exceeded\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final GatewayRateLimitProperties properties;
    private final GatewayRateLimiter rateLimiter;

    RateLimitingFilter(GatewayRateLimitProperties properties, GatewayRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }

        return rateLimiter.tryAcquire(exchange)
                .flatMap(allowed -> {
                    if (Boolean.TRUE.equals(allowed)) {
                        return chain.filter(exchange);
                    }
                    return reject(exchange);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory()
                .wrap(RATE_LIMIT_RESPONSE)));
    }
}
