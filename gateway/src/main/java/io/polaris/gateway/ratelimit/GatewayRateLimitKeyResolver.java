package io.polaris.gateway.ratelimit;

import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

class GatewayRateLimitKeyResolver {
    private final GatewayRateLimitProperties properties;

    GatewayRateLimitKeyResolver(GatewayRateLimitProperties properties) {
        this.properties = properties;
    }

    Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .filter(StringUtils::hasText)
                .switchIfEmpty(Mono.fromSupplier(() -> remoteAddressKey(exchange)));
    }

    private String remoteAddressKey(ServerWebExchange exchange) {
        String headerValue = exchange.getRequest().getHeaders().getFirst(properties.keyHeader());
        if (StringUtils.hasText(headerValue)) {
            return headerValue.split(",", 2)[0].trim();
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null) {
            return "unknown";
        }
        if (remoteAddress.getAddress() == null) {
            return remoteAddress.getHostString();
        }
        return remoteAddress.getAddress().getHostAddress();
    }
}
