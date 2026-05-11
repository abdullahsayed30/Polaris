package io.polaris.gateway.logging;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
class RequestLoggingFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime();
        String requestId = resolveRequestId(exchange);
        AtomicBoolean logged = new AtomicBoolean();

        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
            return Mono.empty();
        });

        return chain.filter(exchange)
                .doOnError(error -> logRequest(exchange, started, requestId, logged, error))
                .doFinally(signal -> logRequest(exchange, started, requestId, logged, null));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        String provided = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (StringUtils.hasText(provided)) {
            return provided;
        }
        return exchange.getRequest().getId();
    }

    private void logRequest(
            ServerWebExchange exchange,
            long started,
            String requestId,
            AtomicBoolean logged,
            Throwable error) {
        if (!logged.compareAndSet(false, true)) {
            return;
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        int status = statusCode == null ? 500 : statusCode.value();
        String remoteAddress = remoteAddress(exchange);

        log.info(
                "gateway request method={} path={} status={} durationMs={} requestId={} remoteAddress={} errorType={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                status,
                durationMs,
                requestId,
                remoteAddress,
                error == null ? "none" : error.getClass().getName());
    }

    private String remoteAddress(ServerWebExchange exchange) {
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
