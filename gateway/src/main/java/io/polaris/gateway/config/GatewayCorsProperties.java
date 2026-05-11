package io.polaris.gateway.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "polaris.gateway.cors")
public record GatewayCorsProperties(
        @DefaultValue("http://localhost:3000") List<String> allowedOrigins,
        @DefaultValue( {
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) List<String> allowedMethods,
        @DefaultValue({"Authorization", "Content-Type", "X-Request-Id"}) List<String> allowedHeaders,
        @DefaultValue("X-Request-Id") List<String> exposedHeaders,
        @DefaultValue("true") boolean allowCredentials,
        @DefaultValue("1h") Duration maxAge){
}
