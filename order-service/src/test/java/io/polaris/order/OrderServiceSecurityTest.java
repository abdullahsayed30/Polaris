package io.polaris.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.polaris.order.adapter.in.web.OrderController;
import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderItem;
import io.polaris.order.application.domain.service.OrderApplicationService;
import io.polaris.order.application.port.in.OrderNotFoundException;
import io.polaris.order.application.port.in.PlaceOrderResult;

@WebMvcTest(OrderController.class)
@Import({SecurityConfiguration.class, OrderServiceSecurityTest.TokenConfiguration.class})
class OrderServiceSecurityTest {
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String ISSUER = "https://issuer.polaris.test";
    private static final KeyPair SIGNING_KEY = signingKey();
    private static final String VALID_ORDER = """
            {"items":[{"sku":"SKU-1","quantity":1,"unitPrice":1.00}]}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder jwtDecoder;

    @MockitoBean
    OrderApplicationService orderService;

    @Test
    void rejectsUnauthenticatedOrderRequestsWithoutCreatingASession() throws Exception {
        MvcResult response = mvc.perform(get("/api/v1/orders/{id}", ORDER_ID))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(response.getRequest().getSession(false)).isNull();
    }

    @Test
    void preservesInternalServerErrorsWithoutOpeningTheErrorEndpoint() throws Exception {
        mvc.perform(get("/error")
                .with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                })
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/orders"))
                .andExpect(status().isInternalServerError());

        mvc.perform(get("/error")).andExpect(status().isUnauthorized());
        mvc.perform(get("/error").header(HttpHeaders.AUTHORIZATION, bearer("orders:read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void enforcesMethodSpecificOrderScopesThroughSignedBearerHeaders() throws Exception {
        when(orderService.getOrder(ORDER_ID, CUSTOMER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:write")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:read")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:read"))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsSignedBearerPostWithoutCsrfAndDoesNotCarryAuthenticationForward() throws Exception {
        Order order = Order.place(CUSTOMER_ID, List.of(OrderItem.create("SKU-1", 1, new BigDecimal("1.00"))));
        order.confirm();
        when(orderService.placeOrder(any(), any(), any())).thenReturn(new PlaceOrderResult(order, false));

        MvcResult response = mvc.perform(post("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer("orders:write"))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isCreated()).andReturn();
        verify(orderService).placeOrder(any(), eq(CUSTOMER_ID), any());
        assertThat(response.getRequest().getSession(false)).isNull();
        assertThat(response.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "not-a-uuid", "1-1-1-1-1",
            "11111111111141118111111111111111", "11111111-1111-4111-8111-11111111111g",
            "11111111-1111-4111-8111-111111111111 ", " 11111111-1111-4111-8111-111111111111",
            "111111111-111-4111-8111-111111111111"})
    void rejectsInvalidCustomerSubjectsBeforeCallingOrderUseCases(String subject) throws Exception {
        String authorization = "Bearer " + signedToken("orders:read orders:write",
                Instant.now().plusSeconds(300), SIGNING_KEY, subject, ISSUER);
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID)
                .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        mvc.perform(post("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        verifyNoInteractions(orderService);
    }

    @Test
    void rejectsInvalidSignatureExpiredWrongIssuerAndMalformedBearerTokens() throws Exception {
        for (String token : List.of(
                signedToken("orders:write", Instant.now().plusSeconds(300), signingKey()),
                signedToken("orders:write", Instant.now().minusSeconds(300), SIGNING_KEY),
                signedToken("orders:write", Instant.now().plusSeconds(300), SIGNING_KEY, CUSTOMER_ID.toString(),
                        "https://wrong-issuer.test"),
                "not-a-jwt")) {
            mvc.perform(post("/api/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void ignoresAuthenticatedSessionsAndTokenCookies() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new JwtAuthenticationToken(jwtDecoder.decode(token("orders:read orders:write")),
                        List.of(() -> "SCOPE_orders:read", () -> "SCOPE_orders:write"))));

        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID).session(session)
                .cookie(new Cookie("JSESSIONID", session.getId())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID).cookie(new Cookie("access_token", token("orders:read"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").cookie(new Cookie("access_token", token("orders:write")))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsBasicQueryAndFormCredentialsAndKeepsCsrfForNonBearerPosts() throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString("customer:password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID).header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID).param("access_token", token("orders:read")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("access_token", token("orders:write")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/login").with(csrf()).param("username", "customer").param("password", "password"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER))
                .andExpect(status().isUnauthorized());
    }

    private static String bearer(String scope) throws Exception {
        return "Bearer " + token(scope);
    }

    private static String token(String scope) throws Exception {
        return signedToken(scope, Instant.now().plusSeconds(300), SIGNING_KEY);
    }

    private static String signedToken(String scope, Instant expiresAt, KeyPair key) throws Exception {
        return signedToken(scope, expiresAt, key, CUSTOMER_ID.toString(), ISSUER);
    }

    private static String signedToken(String scope, Instant expiresAt, KeyPair key, String subject, String issuer) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(issuer).subject(subject).claim("scope", scope)
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
    static class TokenConfiguration {
        @Bean
        JwtDecoder jwtDecoder(OAuth2TokenValidator<Jwt> customerSubjectValidator) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), customerSubjectValidator));
            return decoder;
        }
    }
}
