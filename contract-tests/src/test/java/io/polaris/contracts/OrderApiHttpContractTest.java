package io.polaris.contracts;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.order.adapter.in.web.ApiExceptionHandler;
import io.polaris.order.adapter.in.web.OrderController;
import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderItem;
import io.polaris.order.application.domain.service.OrderApplicationService;
import io.polaris.order.application.port.in.IdempotencyConflictException;
import io.polaris.order.application.port.in.InvalidIdempotencyKeyException;
import io.polaris.order.application.port.in.OrderNotFoundException;
import io.polaris.order.application.port.in.PlaceOrderResult;
import io.polaris.order.application.port.out.InventoryUnavailableException;

@WebMvcTest(properties = "spring.mvc.problemdetails.enabled=true")
@Import({OrderController.class, ApiExceptionHandler.class})
class OrderApiHttpContractTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    OrderApplicationService orderService;

    @Test
    void validOrderReturnsDocumentedCreatedResponse() throws Exception {
        UUID customerId = UUID.randomUUID();
        Order order = Order.place(customerId, List.of(OrderItem.create("SKU-1", 2, new BigDecimal("12.50"))));
        order.confirm();
        when(orderService.placeOrder(any(), any(), any())).thenReturn(new PlaceOrderResult(order, false));

        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(customerId.toString())).authorities(() -> "SCOPE_orders:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(new PlaceOrderPayload(
                        List.of(new ItemPayload("SKU-1", 2, new BigDecimal("12.50")))))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/orders/" + order.getId())))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(order.getId().toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-1"));
    }

    @Test
    void emptyItemsReturnDocumentedValidationProblem() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                        .authorities(() -> "SCOPE_orders:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "items": []
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value("/api/v1/orders"));
    }

    @Test
    void invalidItemFieldsReturnDocumentedValidationProblem() throws Exception {
        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                        .authorities(() -> "SCOPE_orders:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "items": [{"sku": "", "quantity": 0, "unitPrice": 0}]
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void malformedOrderIdReturnsDocumentedBadRequestProblem() throws Exception {
        mvc.perform(get("/api/v1/orders/not-a-uuid")
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                        .authorities(() -> "SCOPE_orders:read")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingOrderReturnsDocumentedNotFoundExtension() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(orderService.getOrder(orderId, customerId)).thenThrow(new OrderNotFoundException(orderId));

        mvc.perform(get("/api/v1/orders/{id}", orderId)
                .with(jwt().jwt(token -> token.subject(customerId.toString()))
                        .authorities(() -> "SCOPE_orders:read")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }

    @Test
    void inventoryFailureReturnsDocumentedServiceUnavailableProblem() throws Exception {
        when(orderService.placeOrder(any(), any(), any())).thenThrow(
                new InventoryUnavailableException("Inventory service is unavailable", new IllegalStateException("down")));

        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                        .authorities(() -> "SCOPE_orders:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "items": [{"sku": "SKU-1", "quantity": 1, "unitPrice": 1.00}]
                        }
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Inventory unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Inventory service is unavailable"));
    }

    @Test
    void invalidIdempotencyKeyReturnsDocumentedBadRequestProblem() throws Exception {
        UUID customerId = UUID.randomUUID();
        when(orderService.placeOrder(any(), any(), any()))
                .thenThrow(new InvalidIdempotencyKeyException("Idempotency-Key must not be blank"));

        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(customerId.toString())).authorities(() -> "SCOPE_orders:write"))
                .header("Idempotency-Key", " ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid idempotency key"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadReturnsDocumentedConflict() throws Exception {
        UUID customerId = UUID.randomUUID();
        when(orderService.placeOrder(any(), any(), any())).thenThrow(new IdempotencyConflictException("key-1"));

        mvc.perform(post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject(customerId.toString())).authorities(() -> "SCOPE_orders:write"))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Idempotency conflict"))
                .andExpect(jsonPath("$.status").value(409));
    }

    private String validRequest() {
        return """
                {
                  "items": [{"sku": "SKU-1", "quantity": 1, "unitPrice": 1.00}]
                }
                """;
    }

    record PlaceOrderPayload(List<ItemPayload> items) {
    }

    record ItemPayload(String sku, int quantity, BigDecimal unitPrice) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
