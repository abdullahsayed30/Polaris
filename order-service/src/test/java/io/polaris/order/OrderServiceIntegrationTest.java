package io.polaris.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.StockItemAvailability;
import io.polaris.inventory.grpc.StockRequest;
import io.polaris.inventory.grpc.StockResponse;
import io.polaris.order.api.OrderItemRequest;
import io.polaris.order.api.OrderResponse;
import io.polaris.order.api.PlaceOrderRequest;
import io.polaris.order.domain.OrderStatus;
import io.polaris.shared.events.OrderCreatedEvent;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceIntegrationTest {
    private static final String ORDER_CREATED_TOPIC = "polaris.orders.created";
    private static final FakeInventoryService fakeInventoryService = new FakeInventoryService();
    private static final Server inventoryServer;
    private static final int inventoryPort;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("polaris_orders")
            .withUsername("polaris_order")
            .withPassword("polaris_order");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

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

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("polaris.inventory.grpc.host", () -> "localhost");
        registry.add("polaris.inventory.grpc.port", () -> inventoryPort);
    }

    @BeforeEach
    void setUp() {
        fakeInventoryService.setAvailable(true);
    }

    @AfterAll
    static void stopInventoryServer() {
        inventoryServer.shutdownNow();
    }

    @Test
    void placeOrderWithAvailableStockConfirmsOrderAndPublishesEvent() throws Exception {
        fakeInventoryService.setAvailable(true);

        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                "/api/v1/orders",
                placeOrderRequest(),
                OrderResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.items()).hasSize(2);

        ResponseEntity<OrderResponse> lookup = restTemplate.getForEntity(
                "/api/v1/orders/{id}",
                OrderResponse.class,
                order.id()
        );
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup.getBody()).isNotNull();
        assertThat(lookup.getBody().id()).isEqualTo(order.id());

        OrderCreatedEvent event = awaitOrderCreatedEvent(order.id());
        assertThat(event.customerId()).isEqualTo(order.customerId());
        assertThat(event.status()).isEqualTo(OrderCreatedEvent.OrderStatus.CONFIRMED);
        assertThat(event.items()).hasSize(2);
    }

    @Test
    void placeOrderWithUnavailableStockCancelsOrderAndPublishesEvent() throws Exception {
        fakeInventoryService.setAvailable(false);

        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                "/api/v1/orders",
                placeOrderRequest(),
                OrderResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);

        OrderCreatedEvent event = awaitOrderCreatedEvent(order.id());
        assertThat(event.status()).isEqualTo(OrderCreatedEvent.OrderStatus.CANCELLED);
    }

    @Test
    void getUnknownOrderReturnsNotFoundProblem() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/orders/{id}",
                String.class,
                UUID.randomUUID()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Order not found");
    }

    @Test
    void placeOrderWithInvalidPayloadReturnsBadRequest() {
        Map<String, Object> invalidRequest = Map.of(
                "customerId", UUID.randomUUID(),
                "items", List.of()
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/orders",
                invalidRequest,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private PlaceOrderRequest placeOrderRequest() {
        return new PlaceOrderRequest(
                UUID.randomUUID(),
                List.of(
                        new OrderItemRequest("SKU-COFFEE-001", 2, new BigDecimal("19.99")),
                        new OrderItemRequest("SKU-MUG-002", 1, new BigDecimal("8.50"))
                )
        );
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

        void setAvailable(boolean available) {
            this.available.set(available);
        }

        @Override
        public void checkStock(StockRequest request, StreamObserver<StockResponse> responseObserver) {
            boolean stockAvailable = available.get();
            StockResponse.Builder response = StockResponse.newBuilder()
                    .setAvailable(stockAvailable)
                    .setReason(stockAvailable ? "available" : "insufficient_stock");

            request.getItemsList().forEach(item -> response.addItems(StockItemAvailability.newBuilder()
                    .setSku(item.getSku())
                    .setRequestedQuantity(item.getQuantity())
                    .setAvailableQuantity(stockAvailable ? item.getQuantity() : 0)
                    .setAvailable(stockAvailable)
                    .build()));

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }
    }
}
