package io.polaris.order;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.polaris.order.adapter.in.web.OrderController;
import io.polaris.order.application.domain.service.OrderApplicationService;
import io.polaris.order.application.port.in.OrderNotFoundException;

@WebMvcTest(OrderController.class)
@Import(SecurityConfiguration.class)
class OrderServiceSecurityTest {
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    OrderApplicationService orderService;

    @Test
    void rejectsUnauthenticatedOrderRequests() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID))
                .andExpect(status().isUnauthorized());
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

        mvc.perform(get("/error"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/error").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void enforcesMethodSpecificOrderScopes() throws Exception {
        when(orderService.getOrder(ORDER_ID, CUSTOMER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID)
                .with(jwt().jwt(token -> token.subject(CUSTOMER_ID.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_orders:write"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID)
                .with(jwt().jwt(token -> token.subject(CUSTOMER_ID.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_orders:read"))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(CUSTOMER_ID.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_orders:read")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(CUSTOMER_ID.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_orders:write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
