package io.polaris.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.polaris.gateway.config.GatewayCorsProperties;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "polaris.gateway.routes.order-service-uri=forward:/__stub/orders",
        "polaris.gateway.rate-limit.enabled=false",
        "polaris.gateway.cors.allowed-origins=http://localhost:3000",
        "polaris.gateway.cors.allowed-methods=GET,POST,PUT,PATCH,DELETE,OPTIONS",
        "polaris.gateway.cors.allowed-headers=Authorization,Content-Type,Idempotency-Key,X-Request-Id,WebTestClient-Request-Id",
        "polaris.gateway.cors.exposed-headers=Idempotency-Replayed,X-Request-Id"
})
@AutoConfigureWebTestClient
class GatewayRouteSecurityTest {
    private static final String ISSUER = "https://issuer.polaris.test";
    private static final KeyPair SIGNING_KEY = signingKey();
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
        assertThat(corsProperties.allowedHeaders()).contains("Idempotency-Key");
        assertThat(corsProperties.exposedHeaders()).contains("Idempotency-Replayed");
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
    void permitsLivenessWithoutAuthenticationOrSessionCookies() {
        webTestClient.get().uri("/actuator/health/liveness")
                .exchange().expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void forwardsAuthenticatedOrderRequests() throws Exception {
        webTestClient.get()
                .uri("/api/v1/orders/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:read"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.backend").isEqualTo("order-service");
    }

    @Test
    void rejectsAuthenticatedOrderRequestsWithoutRequiredScope() throws Exception {
        webTestClient.get()
                .uri("/api/v1/orders/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:write"))
                .exchange()
                .expectStatus().isForbidden();
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

    @Test
    void acceptsSignedBearerPostWithoutCsrfAndDoesNotCarryAuthenticationForward() throws Exception {
        webTestClient.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:write"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"items\":[]}")
                .exchange().expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody().jsonPath("$.backend").isEqualTo("order-service");
        webTestClient.get().uri("/api/v1/orders/{id}", UUID.randomUUID())
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
        webTestClient.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:read"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void rejectsInvalidSignatureExpiredAndMalformedBearerTokens() throws Exception {
        for (String token : List.of(
                signedToken("orders:write", Instant.now().plusSeconds(300), signingKey()),
                signedToken("orders:write", Instant.now().minusSeconds(300), SIGNING_KEY),
                "not-a-jwt")) {
            webTestClient.post().uri("/api/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void ignoresAuthenticatedWebSessionsAndTokenCookies() throws Exception {
        // The test-only filter seeds a real WebSession as though an earlier login had populated it.
        var response = webTestClient.get().uri("/api/v1/orders/{id}", UUID.randomUUID())
                .header("X-Test-Authenticated-Session", token("orders:read orders:write"))
                .exchange().expectStatus().isUnauthorized().returnResult(Void.class);
        var sessionCookie = response.getResponseCookies().getFirst("SESSION");
        assertThat(sessionCookie).isNotNull();
        webTestClient.get().uri("/api/v1/orders/{id}", UUID.randomUUID())
                .cookie("SESSION", sessionCookie.getValue())
                .exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/orders")
                .cookie("SESSION", sessionCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
        webTestClient.get().uri("/api/v1/orders/{id}", UUID.randomUUID())
                .cookie("access_token", token("orders:read"))
                .exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/orders")
                .cookie("access_token", token("orders:write"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void rejectsBasicQueryAndFormCredentialsAndKeepsCsrfForNonBearerPosts() throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                "customer:password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        webTestClient.get().uri("/api/v1/orders/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, basic).exchange().expectStatus().isUnauthorized();
        String queryToken = token("orders:read");
        webTestClient.get().uri(builder -> builder.path("/api/v1/orders/" + UUID.randomUUID())
                .queryParam("access_token", queryToken).build())
                .exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("access_token=" + token("orders:write"))
                .exchange().expectStatus().isForbidden();
        webTestClient.post().uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("username=customer&password=password")
                .exchange().expectStatus().isForbidden();
        webTestClient.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
    }

    private static String bearer(String scope) throws Exception {
        return "Bearer " + token(scope);
    }

    private static String token(String scope) throws Exception {
        return signedToken(scope, Instant.now().plusSeconds(300), SIGNING_KEY);
    }

    private static String signedToken(String scope, Instant expiresAt, KeyPair key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("11111111-1111-4111-8111-111111111111").claim("scope", scope)
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiresAt)).build());
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair signingKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class StubOrderBackendConfiguration {
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                    .withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 2)
        WebFilter authenticatedSessionFixture(ReactiveJwtDecoder decoder) {
            return (exchange, chain) -> {
                String token = exchange.getRequest().getHeaders().getFirst("X-Test-Authenticated-Session");
                if (token == null) {
                    return chain.filter(exchange);
                }
                return decoder.decode(token)
                        .flatMap(jwt -> exchange.getSession()
                                .doOnNext(session -> session.getAttributes().put("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                                        new JwtAuthenticationToken(jwt, List.of(
                                                new SimpleGrantedAuthority("SCOPE_orders:read"),
                                                new SimpleGrantedAuthority("SCOPE_orders:write")))))))
                        .then(Mono.defer(() -> chain.filter(exchange)));
            };
        }

        @Bean
        RouterFunction<ServerResponse> stubOrderBackend() {
            return RouterFunctions.route(path("/__stub/orders"), request -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("backend", "order-service")));
        }
    }
}
