package io.polaris.gateway;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "polaris.gateway.routes.order-service-uri=forward:/__stub/orders",
        "polaris.gateway.rate-limit.enabled=true",
        "polaris.gateway.rate-limit.backend=in-memory",
        "polaris.gateway.rate-limit.requests-per-window=1",
        "polaris.gateway.rate-limit.window=1m"
})
@AutoConfigureWebTestClient
class GatewayRateLimitingTest {
    @Autowired
    WebTestClient webTestClient;

    @Test
    void rejectsRequestsAfterLimitIsExceeded() {
        WebTestClient authenticatedClient = webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> jwt.subject("11111111-1111-4111-8111-111111111111"))
                .authorities(new SimpleGrantedAuthority("SCOPE_orders:read")));

        authenticatedClient.get()
                .uri("/api/v1/orders/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isOk();

        authenticatedClient.get()
                .uri("/api/v1/orders/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("rate_limit_exceeded");
    }

    @TestConfiguration
    static class StubOrderBackendConfiguration {
        @Bean
        @Primary
        Clock rateLimitClock() {
            // Keep both requests in the same window even when wall time crosses a minute boundary.
            return Clock.fixed(Instant.parse("2026-01-01T00:00:30Z"), ZoneOffset.UTC);
        }

        @Bean
        RouterFunction<ServerResponse> stubOrderBackend() {
            return RouterFunctions.route(path("/__stub/orders"), request -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("backend", "order-service")));
        }
    }
}
