package io.polaris.gateway;

import io.polaris.gateway.config.GatewayCorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "polaris.gateway.routes.order-service-uri=forward:/__stub/orders",
        "polaris.gateway.rate-limit.enabled=false",
        "polaris.gateway.cors.allowed-origins=http://localhost:3000",
        "polaris.gateway.cors.allowed-methods=GET,POST,PUT,PATCH,DELETE,OPTIONS",
        "polaris.gateway.cors.allowed-headers=Authorization,Content-Type,X-Request-Id,WebTestClient-Request-Id"
})
@AutoConfigureWebTestClient
class GatewayRouteSecurityTest {
    @Autowired
    WebTestClient webTestClient;

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    GatewayCorsProperties corsProperties;

    @Autowired
    CorsConfigurationSource corsConfigurationSource;

    @LocalServerPort
    int port;

    @Test
    void loadsThreeOrderRouteDefinitions() {
        List<String> routeIds = routeDefinitionLocator.getRouteDefinitions()
                .map(RouteDefinition::getId)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(routeIds).containsExactly("order-create", "order-read", "order-public-api");
    }

    @Test
    void corsConfigurationAllowsLocalhostPreflight() {
        MockServerHttpRequest request = MockServerHttpRequest.options("/api/v1/orders")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "WebTestClient-Request-Id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(exchange);

        assertThat(corsProperties.allowedOrigins()).contains("http://localhost:3000");
        assertThat(corsProperties.allowedMethods()).contains("POST");
        assertThat(corsProperties.allowedHeaders()).contains("WebTestClient-Request-Id");
        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(configuration.checkHttpMethod(HttpMethod.POST)).contains(HttpMethod.POST);
        assertThat(configuration.checkHeaders(List.of("WebTestClient-Request-Id")))
                .contains("WebTestClient-Request-Id");
    }

    @Test
    void rejectsUnauthenticatedOrderRequests() {
        webTestClient.get()
                .uri("/api/v1/orders/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void forwardsAuthenticatedOrderRequests() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject("customer-123")))
                .get()
                .uri("/api/v1/orders/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.backend").isEqualTo("order-service");
    }

    @Test
    void permitsConfiguredCorsPreflight() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .contains("http://localhost:3000");
        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .contains("true");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @TestConfiguration
    static class StubOrderBackendConfiguration {
        @Bean
        RouterFunction<ServerResponse> stubOrderBackend() {
            return RouterFunctions.route(path("/__stub/orders"), request -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("backend", "order-service")));
        }
    }
}
