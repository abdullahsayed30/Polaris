package io.polaris.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import io.polaris.inventory.grpc.InventoryDecision;
import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.ReserveRequest;
import io.polaris.inventory.grpc.ReserveResponse;
import io.polaris.inventory.grpc.StockItemAvailability;
import io.polaris.inventory.grpc.StockItemReservation;
import io.polaris.inventory.grpc.StockRequest;
import io.polaris.inventory.grpc.StockResponse;
import io.polaris.order.adapter.in.web.OrderItemRequest;
import io.polaris.order.adapter.in.web.OrderResponse;
import io.polaris.order.adapter.in.web.PlaceOrderRequest;
import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderItem;
import io.polaris.order.application.domain.model.OrderStatus;
import io.polaris.order.application.domain.service.ReservationRecovery;
import io.polaris.order.application.port.out.OrderEventRecorder;
import io.polaris.order.application.port.out.OrderStore;
import io.polaris.shared.events.OrderCreatedEvent;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(OrderServiceIntegrationTest.TestSecurityConfiguration.class)
class OrderServiceIntegrationTest {
    private static final String ORDER_CREATED_TOPIC = "polaris.orders.created";
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final FakeInventoryService fakeInventoryService = new FakeInventoryService();
    private static final Server inventoryServer;
    private static final int inventoryPort;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("polaris_orders")
            .withUsername("polaris_order")
            .withPassword("polaris_order");

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static {
        try {
            inventoryServer = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forPort(0)
                    .addService(fakeInventoryService)
                    .build()
                    .start();
            inventoryPort = inventoryServer.getPort();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReservationRecovery recovery;

    @Autowired
    OrderStore orderStore;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    OrderEventRecorder eventRecorder;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("polaris.inventory.grpc.host", () -> "localhost");
        registry.add("polaris.inventory.grpc.port", () -> inventoryPort);
        registry.add("polaris.reservation.recovery.initial-delay", () -> "1h");
    }

    @BeforeEach
    void setUp() {
        fakeInventoryService.setAvailable(true);
        fakeInventoryService.setReserved(true);
        fakeInventoryService.resetCalls();
    }

    @AfterAll
    static void stopInventoryServer() {
        inventoryServer.shutdownNow();
    }

    @Test
    void placeOrderWithAvailableStockConfirmsOrderAndPublishesEvent() throws Exception {
        fakeInventoryService.setAvailable(true);

        ResponseEntity<OrderResponse> response = placeOrder(placeOrderRequest(), CUSTOMER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(order.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.items()).hasSize(2);

        ResponseEntity<OrderResponse> lookup = getOrder(order.id(), CUSTOMER_ID, OrderResponse.class);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup.getBody()).isNotNull();
        assertThat(lookup.getBody().id()).isEqualTo(order.id());

        OrderCreatedEvent event = awaitOrderCreatedEvent(order.id());
        assertThat(event.customerId()).isEqualTo(order.customerId());
        assertThat(event.status()).isEqualTo(OrderCreatedEvent.OrderStatus.CONFIRMED);
        assertThat(event.items()).hasSize(2);
        assertThat(fakeInventoryService.checkStockCalls()).isZero();
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(1);
    }

    @Test
    void atomicReservationIsAuthoritativeWhenEarlierStockCheckWouldBeStale() throws Exception {
        fakeInventoryService.setAvailable(false);
        fakeInventoryService.setReserved(true);

        ResponseEntity<OrderResponse> response = placeOrder(placeOrderRequest(), CUSTOMER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);

        OrderCreatedEvent event = awaitOrderCreatedEvent(order.id());
        assertThat(event.status()).isEqualTo(OrderCreatedEvent.OrderStatus.CONFIRMED);
        assertThat(fakeInventoryService.checkStockCalls()).isZero();
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(1);
    }

    @Test
    void placeOrderWhenReservationFailsCancelsOrderAndPublishesEvent() throws Exception {
        fakeInventoryService.setAvailable(true);
        fakeInventoryService.setReserved(false);

        ResponseEntity<OrderResponse> response = placeOrder(placeOrderRequest(), CUSTOMER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);

        OrderCreatedEvent event = awaitOrderCreatedEvent(order.id());
        assertThat(event.status()).isEqualTo(OrderCreatedEvent.OrderStatus.CANCELLED);
        assertThat(fakeInventoryService.checkStockCalls()).isZero();
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(1);
    }

    @Test
    void duplicateIdempotencyKeyReplaysOrderWithoutCallingInventoryAgain() {
        String idempotencyKey = "checkout-" + UUID.randomUUID();

        ResponseEntity<OrderResponse> first = placeOrder(placeOrderRequest(), CUSTOMER_ID, idempotencyKey);
        ResponseEntity<OrderResponse> replay = placeOrder(placeOrderRequest(), CUSTOMER_ID, idempotencyKey);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).isNotNull();
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(1);
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentPayload() {
        String idempotencyKey = "checkout-" + UUID.randomUUID();
        placeOrder(placeOrderRequest(), CUSTOMER_ID, idempotencyKey);
        PlaceOrderRequest differentRequest = new PlaceOrderRequest(
                List.of(new OrderItemRequest("SKU-COFFEE-001", 3, new BigDecimal("19.99"))));

        ResponseEntity<String> conflict = placeOrder(
                differentRequest, CUSTOMER_ID, idempotencyKey, String.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("Idempotency conflict");
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateHttpRequestsCreateOneOrder() throws Exception {
        String idempotencyKey = "checkout-" + UUID.randomUUID();
        PlaceOrderRequest request = placeOrderRequest();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<OrderResponse>> first = executor.submit(() -> {
                start.await();
                return placeOrder(request, CUSTOMER_ID, idempotencyKey);
            });
            Future<ResponseEntity<OrderResponse>> second = executor.submit(() -> {
                start.await();
                return placeOrder(request, CUSTOMER_ID, idempotencyKey);
            });
            start.countDown();

            OrderResponse firstOrder = first.get(10, TimeUnit.SECONDS).getBody();
            OrderResponse secondOrder = second.get(10, TimeUnit.SECONDS).getBody();
            assertThat(firstOrder).isNotNull();
            assertThat(secondOrder).isNotNull();
            assertThat(secondOrder.id()).isEqualTo(firstOrder.id());
        }

        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(1);
    }

    @Test
    void retryAfterLostReservationResponseUsesSameOrderId() {
        String idempotencyKey = "checkout-" + UUID.randomUUID();
        fakeInventoryService.loseNextReservationResponse();

        ResponseEntity<String> failed = placeOrder(
                placeOrderRequest(), CUSTOMER_ID, idempotencyKey, String.class);
        ResponseEntity<OrderResponse> retry = placeOrder(placeOrderRequest(), CUSTOMER_ID, idempotencyKey);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isNotNull();
        assertThat(retry.getBody().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(2);
        assertThat(fakeInventoryService.reservedOrderIds()).hasSize(1);
    }

    @Test
    void recoveryCompletesHeaderlessOrderAfterLostResponseWithoutClientRetry() throws Exception {
        fakeInventoryService.loseNextReservationResponse();
        ResponseEntity<String> failed = placeOrder(placeOrderRequest(), CUSTOMER_ID, null, String.class);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        UUID orderId = UUID.fromString(fakeInventoryService.reservedOrderIds().iterator().next());
        assertThat(getOrder(orderId, CUSTOMER_ID, OrderResponse.class).getBody().status())
                .isEqualTo(OrderStatus.PENDING);

        makeRecoveryDue(orderId);
        recovery.recoverPending();
        recovery.recoverPending();

        assertThat(getOrder(orderId, CUSTOMER_ID, OrderResponse.class).getBody().status())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fakeInventoryService.reservedOrderIds()).containsExactly(orderId.toString());
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(2);
        assertThat(awaitOrderCreatedEvent(orderId).status()).isEqualTo(OrderCreatedEvent.OrderStatus.CONFIRMED);
        assertSingleOutboxEvent(orderId);
    }

    @Test
    void recoveryCompletesOrderWhenFinalizationRollsBackAfterInventorySuccess() {
        String key = "recovery-" + UUID.randomUUID();
        doThrow(new IllegalStateException("simulated outbox persistence failure"))
                .doCallRealMethod().when(AopTestUtils.<OrderEventRecorder>getUltimateTargetObject(eventRecorder)).enqueue(any());
        ResponseEntity<String> failed = placeOrder(placeOrderRequest(), CUSTOMER_ID, key, String.class);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        UUID orderId = UUID.fromString(fakeInventoryService.reservedOrderIds().iterator().next());
        assertThat(getOrder(orderId, CUSTOMER_ID, OrderResponse.class).getBody().status())
                .isEqualTo(OrderStatus.PENDING);

        PlaceOrderRequest changed = new PlaceOrderRequest(
                List.of(new OrderItemRequest("SKU-COFFEE-001", 3, new BigDecimal("19.99"))));
        assertThat(placeOrder(changed, CUSTOMER_ID, key, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        makeRecoveryDue(orderId);
        recovery.recoverPending();

        assertThat(getOrder(orderId, CUSTOMER_ID, OrderResponse.class).getBody().status())
                .isEqualTo(OrderStatus.CONFIRMED);
        ResponseEntity<OrderResponse> replay = placeOrder(placeOrderRequest(), CUSTOMER_ID, key);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().id()).isEqualTo(orderId);
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(2);
        assertSingleOutboxEvent(orderId);
    }

    private void makeRecoveryDue(UUID orderId) {
        jdbc.update("UPDATE orders SET reservation_retry_at = CURRENT_TIMESTAMP WHERE id = ?", orderId);
    }

    @Test
    void concurrentRecoveryWorkersFinalizeOnlyOnce() throws Exception {
        fakeInventoryService.loseNextReservationResponse();
        placeOrder(placeOrderRequest(), CUSTOMER_ID, null, String.class);
        UUID orderId = UUID.fromString(fakeInventoryService.reservedOrderIds().iterator().next());
        makeRecoveryDue(orderId);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                start.await();
                recovery.recoverPending();
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                recovery.recoverPending();
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(getOrder(orderId, CUSTOMER_ID, OrderResponse.class).getBody().status())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fakeInventoryService.reserveStockCalls()).isEqualTo(2);
        assertSingleOutboxEvent(orderId);
    }

    private void assertSingleOutboxEvent(UUID orderId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Long.class, orderId.toString())).isEqualTo(1L);
    }

    @Test
    void getUnknownOrderReturnsNotFoundProblem() {
        ResponseEntity<String> response = getOrder(UUID.randomUUID(), CUSTOMER_ID, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Order not found");
    }

    @Test
    void customerCannotReadAnotherCustomersOrder() {
        OrderResponse order = placeOrder(placeOrderRequest(), CUSTOMER_ID).getBody();
        assertThat(order).isNotNull();

        ResponseEntity<String> response = getOrder(order.id(), OTHER_CUSTOMER_ID, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requestWithoutRequiredScopeIsForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("no-scope");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/orders/{id}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class,
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void placeOrderWithInvalidPayloadReturnsBadRequest() {
        Map<String, Object> invalidRequest = Map.of(
                "items", List.of());

        HttpHeaders headers = bearerHeaders(CUSTOMER_ID);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST, new HttpEntity<>(invalidRequest, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void orderMappingPreservesItemIdentityDecimalScaleAndOptimisticVersion() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        BigDecimal price = new BigDecimal("90071992547409.91");
        Order created = tx.execute(status -> orderStore.create(
                Order.place(CUSTOMER_ID, List.of(OrderItem.create("SKU-PRECISE", 1, price)))));
        tx.executeWithoutResult(status -> orderStore.findForUpdate(created.getId()).orElseThrow().confirm());
        Order unchanged = tx.execute(status -> orderStore.findForUpdate(created.getId()).orElseThrow());
        assertThat(unchanged.getStatus()).isEqualTo(io.polaris.order.application.domain.model.OrderStatus.PENDING);
        Order saved = tx.execute(status -> {
            Order locked = orderStore.findForUpdate(created.getId()).orElseThrow();
            locked.confirm();
            return orderStore.update(locked);
        });
        Order reloaded = tx.execute(status -> orderStore.findWithItemsByIdAndCustomerId(created.getId(), CUSTOMER_ID).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(io.polaris.order.application.domain.model.OrderStatus.CONFIRMED);
        assertThat(reloaded.getVersion()).isEqualTo(created.getVersion() + 1).isEqualTo(saved.getVersion());
        assertThat(reloaded.getCreatedAt()).isEqualTo(unchanged.getCreatedAt());
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(unchanged.getUpdatedAt());
        assertThat(reloaded.getItems().getFirst().getId()).isEqualTo(created.getItems().getFirst().getId());
        assertThat(reloaded.getItems().getFirst().getUnitPrice()).isEqualTo(price);
        assertThat(reloaded.getItems().getFirst().getUnitPrice().scale()).isEqualTo(2);
    }

    private PlaceOrderRequest placeOrderRequest() {
        return new PlaceOrderRequest(
                List.of(
                        new OrderItemRequest("SKU-COFFEE-001", 2, new BigDecimal("19.99")),
                        new OrderItemRequest("SKU-MUG-002", 1, new BigDecimal("8.50"))));
    }

    private ResponseEntity<OrderResponse> placeOrder(PlaceOrderRequest request, UUID customerId) {
        return placeOrder(request, customerId, null, OrderResponse.class);
    }

    private ResponseEntity<OrderResponse> placeOrder(
            PlaceOrderRequest request, UUID customerId, String idempotencyKey) {
        return placeOrder(request, customerId, idempotencyKey, OrderResponse.class);
    }

    private <T> ResponseEntity<T> placeOrder(
            PlaceOrderRequest request,
            UUID customerId,
            String idempotencyKey,
            Class<T> responseType) {
        HttpHeaders headers = bearerHeaders(customerId);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(
                "/api/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                responseType);
    }

    private <T> ResponseEntity<T> getOrder(UUID orderId, UUID customerId, Class<T> responseType) {
        return restTemplate.exchange(
                "/api/v1/orders/{id}",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerId)),
                responseType,
                orderId);
    }

    private HttpHeaders bearerHeaders(UUID customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerId.toString());
        return headers;
    }

    private OrderCreatedEvent awaitOrderCreatedEvent(UUID orderId) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(ORDER_CREATED_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();

            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    OrderCreatedEvent event = objectMapper.readValue(record.value(), OrderCreatedEvent.class);
                    if (orderId.equals(event.orderId())) {
                        return event;
                    }
                }
            }
        }

        return fail("Timed out waiting for OrderCreatedEvent with id " + orderId);
    }

    private static final class FakeInventoryService extends InventoryServiceGrpc.InventoryServiceImplBase {
        private final AtomicBoolean available = new AtomicBoolean(true);
        private final AtomicBoolean reserved = new AtomicBoolean(true);
        private final AtomicBoolean loseNextReservationResponse = new AtomicBoolean();
        private final AtomicInteger checkStockCalls = new AtomicInteger();
        private final AtomicInteger reserveStockCalls = new AtomicInteger();
        private final Set<String> reservedOrderIds = ConcurrentHashMap.newKeySet();

        void setAvailable(boolean available) {
            this.available.set(available);
        }

        void setReserved(boolean reserved) {
            this.reserved.set(reserved);
        }

        void resetCalls() {
            checkStockCalls.set(0);
            reserveStockCalls.set(0);
            loseNextReservationResponse.set(false);
            reservedOrderIds.clear();
        }

        void loseNextReservationResponse() {
            loseNextReservationResponse.set(true);
        }

        int checkStockCalls() {
            return checkStockCalls.get();
        }

        int reserveStockCalls() {
            return reserveStockCalls.get();
        }

        Set<String> reservedOrderIds() {
            return Set.copyOf(reservedOrderIds);
        }

        @Override
        public void checkStock(StockRequest request, StreamObserver<StockResponse> responseObserver) {
            checkStockCalls.incrementAndGet();
            boolean stockAvailable = available.get();
            StockResponse.Builder response = StockResponse.newBuilder()
                    .setAvailable(stockAvailable)
                    .setReason(stockAvailable
                            ? InventoryDecision.INVENTORY_DECISION_AVAILABLE
                            : InventoryDecision.INVENTORY_DECISION_INSUFFICIENT_STOCK);

            request.getItemsList().forEach(item -> response.addItems(StockItemAvailability.newBuilder()
                    .setSku(item.getSku())
                    .setRequestedQuantity(item.getQuantity())
                    .setAvailableQuantity(stockAvailable ? item.getQuantity() : 0)
                    .setAvailable(stockAvailable)
                    .build()));

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }

        @Override
        public void reserveStock(ReserveRequest request, StreamObserver<ReserveResponse> responseObserver) {
            reserveStockCalls.incrementAndGet();
            reservedOrderIds.add(request.getOrderId());
            if (loseNextReservationResponse.getAndSet(false)) {
                responseObserver.onError(Status.UNAVAILABLE
                        .withDescription("simulated lost reservation response")
                        .asRuntimeException());
                return;
            }
            boolean stockReserved = reserved.get();
            ReserveResponse.Builder response = ReserveResponse.newBuilder()
                    .setReserved(stockReserved)
                    .setReason(stockReserved
                            ? InventoryDecision.INVENTORY_DECISION_RESERVED
                            : InventoryDecision.INVENTORY_DECISION_INSUFFICIENT_STOCK);

            request.getItemsList().forEach(item -> response.addItems(StockItemReservation.newBuilder()
                    .setSku(item.getSku())
                    .setRequestedQuantity(item.getQuantity())
                    .setReservedQuantity(stockReserved ? item.getQuantity() : 0)
                    .setRemainingQuantity(stockReserved ? 0 : item.getQuantity())
                    .setReserved(stockReserved)
                    .build()));

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }
    }

    @TestConfiguration
    static class TestSecurityConfiguration {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                boolean hasScopes = !"no-scope".equals(token);
                String subject = hasScopes ? token : CUSTOMER_ID.toString();
                Jwt.Builder jwt = Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(subject);
                if (hasScopes) {
                    jwt.claim("scope", "orders:read orders:write");
                }
                return jwt.build();
            };
        }
    }
}
