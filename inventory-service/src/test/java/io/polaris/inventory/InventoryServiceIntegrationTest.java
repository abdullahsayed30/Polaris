package io.polaris.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;

import io.polaris.inventory.domain.InventoryItem;
import io.polaris.inventory.grpc.InventoryDecision;
import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.ReserveRequest;
import io.polaris.inventory.grpc.ReserveResponse;
import io.polaris.inventory.grpc.StockItem;
import io.polaris.inventory.grpc.StockRequest;
import io.polaris.inventory.grpc.StockResponse;
import io.polaris.inventory.persistence.InventoryItemRepository;
import io.polaris.shared.events.InventoryAdjustedEvent;

import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;

@SpringBootTest
@Testcontainers
class InventoryServiceIntegrationTest {
    private static final String INVENTORY_ADJUSTED_TOPIC = "polaris.inventory.adjusted";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("polaris_inventory")
            .withUsername("polaris_inventory")
            .withPassword("polaris_inventory");

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    InventoryItemRepository inventoryItems;

    @Autowired
    GrpcServerPortCapture grpcServer;

    @Autowired
    ObjectMapper objectMapper;

    ManagedChannel channel;
    InventoryServiceGrpc.InventoryServiceBlockingStub inventory;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("polaris.inventory.grpc.port", () -> 0);
        registry.add("grpc.server.reflection-service-enabled", () -> true);
    }

    @BeforeEach
    void setUp() {
        inventoryItems.deleteAll();
        inventoryItems.saveAll(List.of(
                InventoryItem.create("SKU-COFFEE-001", 10),
                InventoryItem.create("SKU-MUG-002", 5)));

        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.port())
                .usePlaintext()
                .build();
        inventory = InventoryServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    @Test
    void checkStockAndReserveStockOverGrpc() throws Exception {
        UUID orderId = UUID.randomUUID();

        StockResponse stock = inventory.checkStock(StockRequest.newBuilder()
                .setOrderId(orderId.toString())
                .addItems(stockItem("SKU-COFFEE-001", 2))
                .addItems(stockItem("SKU-MUG-002", 1))
                .build());

        assertThat(stock.getAvailable()).isTrue();
        assertThat(stock.getItemsList()).hasSize(2);

        ReserveResponse reservation = inventory.reserveStock(ReserveRequest.newBuilder()
                .setOrderId(orderId.toString())
                .addItems(stockItem("SKU-COFFEE-001", 2))
                .addItems(stockItem("SKU-MUG-002", 1))
                .build());

        assertThat(reservation.getReserved()).isTrue();
        assertThat(reservation.getItemsList()).extracting("remainingQuantity").containsExactly(8, 4);
        assertThat(inventoryItems.findBySku("SKU-COFFEE-001")).isPresent()
                .get()
                .extracting(InventoryItem::getAvailableQuantity)
                .isEqualTo(8);

        InventoryAdjustedEvent event = awaitInventoryAdjustedEvent(orderId);
        assertThat(event.items()).hasSize(2);
        assertThat(event.items()).extracting(InventoryAdjustedEvent.Item::quantityChanged).containsExactly(-2, -1);
    }

    @Test
    void reserveStockWithInsufficientStockDoesNotMutateInventory() {
        UUID orderId = UUID.randomUUID();

        ReserveResponse reservation = inventory.reserveStock(ReserveRequest.newBuilder()
                .setOrderId(orderId.toString())
                .addItems(stockItem("SKU-COFFEE-001", 20))
                .build());

        assertThat(reservation.getReserved()).isFalse();
        assertThat(reservation.getReason()).isEqualTo(InventoryDecision.INVENTORY_DECISION_INSUFFICIENT_STOCK);
        assertThat(inventoryItems.findBySku("SKU-COFFEE-001")).isPresent()
                .get()
                .extracting(InventoryItem::getAvailableQuantity)
                .isEqualTo(10);
    }

    @Test
    void grpcHealthAndReflectionAreAvailable() throws Exception {
        HealthCheckResponse health = HealthGrpc.newBlockingStub(channel)
                .check(HealthCheckRequest.newBuilder()
                        .setService("polaris.inventory.v1.InventoryService")
                        .build());
        assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);

        List<ServerReflectionResponse> responses = listGrpcServices();
        assertThat(responses)
                .flatExtracting(response -> response.getListServicesResponse().getServiceList())
                .extracting(ServiceResponse::getName)
                .contains("polaris.inventory.v1.InventoryService");
    }

    private StockItem stockItem(String sku, int quantity) {
        return StockItem.newBuilder()
                .setSku(sku)
                .setQuantity(quantity)
                .build();
    }

    private InventoryAdjustedEvent awaitInventoryAdjustedEvent(UUID orderId) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "inventory-service-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(INVENTORY_ADJUSTED_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();

            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    InventoryAdjustedEvent event = objectMapper.readValue(record.value(), InventoryAdjustedEvent.class);
                    if (orderId.equals(event.orderId())) {
                        return event;
                    }
                }
            }
        }

        return fail("Timed out waiting for InventoryAdjustedEvent with id " + orderId);
    }

    private List<ServerReflectionResponse> listGrpcServices() throws Exception {
        List<ServerReflectionResponse> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        StreamObserver<ServerReflectionRequest> requests = ServerReflectionGrpc.newStub(channel)
                .serverReflectionInfo(new StreamObserver<>() {
                    @Override
                    public void onNext(ServerReflectionResponse response) {
                        responses.add(response);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        error.set(throwable);
                        completed.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        completed.countDown();
                    }
                });

        requests.onNext(ServerReflectionRequest.newBuilder()
                .setListServices("")
                .build());
        requests.onCompleted();

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        return responses;
    }

    @TestConfiguration
    static class GrpcTestConfiguration {
        @Bean
        GrpcServerPortCapture grpcServerPortCapture() {
            return new GrpcServerPortCapture();
        }
    }

    static class GrpcServerPortCapture {
        private volatile int port;

        @EventListener
        void onGrpcServerStarted(GrpcServerStartedEvent event) {
            this.port = event.getPort();
        }

        int port() {
            assertThat(port).isPositive();
            return port;
        }
    }
}
