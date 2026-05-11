package io.polaris.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "polaris.gateway.rate-limit")
public record GatewayRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("IN_MEMORY") Backend backend,
        @DefaultValue("120") int requestsPerWindow,
        @DefaultValue("1m") Duration window,
        @DefaultValue("X-Forwarded-For") String keyHeader
) {
    public enum Backend {
        IN_MEMORY,
        REDIS
    }
}
