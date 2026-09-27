package io.polaris.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.inventory.InventoryServiceApplication;
import io.polaris.inventory.domain.InventoryItem;
import io.polaris.inventory.persistence.InventoryItemRepository;
import io.polaris.notification.NotificationServiceApplication;
import io.polaris.notification.application.NotificationHandler;
import io.polaris.order.OrderServiceApplication;
import io.polaris.order.persistence.OrderRepository;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

import reactor.core.publisher.Mono;

@Testcontainers(disabledWithoutDocker = true)
class OrderLifecycleSystemIntegrationTest {
    private static final String ORDER_CREATED_TOPIC = "polaris.orders.created";
    private static final String INVENTORY_ADJUSTED_TOPIC = "polaris.inventory.adjusted";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Container
    static final PostgreSQLContainer<?> orderPostgres = postgres("polaris_orders", "polaris_order");

    @Container
    static final PostgreSQLContainer<?> inventoryPostgres = postgres("polaris_inventory", "polaris_inventory");

    @Container
    static final PostgreSQLContainer<?> notificationPostgres = postgres("polaris_notifications", "polaris_notification");

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static ConfigurableApplicationContext inventoryContext;
    static ConfigurableApplicationContext notificationContext;
    static ConfigurableApplicationContext orderContext;
    static ConfigurableApplicationContext gatewayContext;
    static RecordingNotificationHandler notifications;
    static int gatewayPort;

    @BeforeAll
    static void startSystem() throws Exception {
        int inventoryGrpcPort = availablePort();
        initializeDatabase(orderPostgres, "order-service");
        initializeDatabase(inventoryPostgres, "inventory-service");
        initializeDatabase(notificationPostgres, "notification-service");

        inventoryContext = new SpringApplicationBuilder(InventoryServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties(inventoryProperties(inventoryGrpcPort))
                .run();
        InventoryItemRepository inventory = inventoryContext.getBean(InventoryItemRepository.class);
        inventory.save(InventoryItem.create("SKU-SYSTEM-001", 10));

        notificationContext = new SpringApplicationBuilder(
                NotificationServiceApplication.class,
                NotificationRecordingConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(notificationProperties())
                .run();
        notifications = notificationContext.getBean(RecordingNotificationHandler.class);

        orderContext = new SpringApplicationBuilder(OrderServiceApplication.class, TestJwtConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .properties(orderProperties(inventoryGrpcPort))
                .run();
        int orderPort = webPort(orderContext);

        gatewayContext = new SpringApplicationBuilder(
                gatewayApplicationClass(), TestJwtConfiguration.class, GatewayTestConfiguration.class)
                .web(WebApplicationType.REACTIVE)
                .properties(gatewayProperties(orderPort))
                .run();
        gatewayPort = webPort(gatewayContext);
    }

    @AfterAll
    static void stopSystem() {
        close(gatewayContext);
        close(orderContext);
        close(notificationContext);
        close(inventoryContext);
    }

    @Test
    void authenticatedGatewayRequestTraversesOrderInventoryKafkaAndNotification() throws Exception {
        UUID customerId = UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + gatewayPort + "/api/v1/orders"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + customerId)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "system-contract-test")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "items": [{"sku": "SKU-SYSTEM-001", "quantity": 2, "unitPrice": 19.99}]
                        }
                        """))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("X-Request-Id")).contains("system-contract-test");
        JsonNode body = JSON.readTree(response.body());
        UUID orderId = UUID.fromString(body.path("id").asText());
        assertThat(body.path("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/orders/" + orderId);

        assertThat(notifications.awaitOrder(orderId, Duration.ofSeconds(15))).isTrue();
        assertThat(notifications.awaitInventory(orderId, Duration.ofSeconds(15))).isTrue();
        assertThat(notifications.order().status()).isEqualTo(OrderCreatedEvent.OrderStatus.CONFIRMED);
        assertThat(notifications.inventory().items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.sku()).isEqualTo("SKU-SYSTEM-001");
                    assertThat(item.quantityChanged()).isEqualTo(-2);
                    assertThat(item.availableQuantity()).isEqualTo(8);
                });

        assertThat(orderContext.getBean(OrderRepository.class).findById(orderId)).isPresent();
        assertThat(inventoryContext.getBean(InventoryItemRepository.class).findBySku("SKU-SYSTEM-001"))
                .isPresent()
                .get()
                .extracting(InventoryItem::getAvailableQuantity)
                .isEqualTo(8);
    }

    private static Map<String, Object> inventoryProperties(int grpcPort) {
        Map<String, Object> properties = commonProperties("inventory-system-test");
        properties.put("spring.datasource.url", inventoryPostgres.getJdbcUrl());
        properties.put("spring.datasource.username", inventoryPostgres.getUsername());
        properties.put("spring.datasource.password", inventoryPostgres.getPassword());
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.open-in-view", "false");
        properties.put("spring.liquibase.enabled", "false");
        properties.put("spring.kafka.consumer.group-id", "inventory-system-test");
        properties.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        properties.put("spring.kafka.consumer.key-deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("spring.kafka.consumer.value-deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        producerProperties(properties);
        properties.put("polaris.kafka.topics.orders-created", ORDER_CREATED_TOPIC);
        properties.put("polaris.kafka.topics.inventory-adjusted", INVENTORY_ADJUSTED_TOPIC);
        properties.put("polaris.inventory.grpc.port", grpcPort);
        properties.put("grpc.server.port", grpcPort);
        properties.put("grpc.server.health-service-enabled", "true");
        return properties;
    }

    private static Map<String, Object> notificationProperties() {
        Map<String, Object> properties = commonProperties("notification-system-test");
        properties.put("spring.datasource.url", notificationPostgres.getJdbcUrl());
        properties.put("spring.datasource.username", notificationPostgres.getUsername());
        properties.put("spring.datasource.password", notificationPostgres.getPassword());
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.open-in-view", "false");
        properties.put("spring.liquibase.enabled", "false");
        properties.put("spring.kafka.consumer.group-id", "notification-system-test");
        properties.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        properties.put("spring.kafka.consumer.key-deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("spring.kafka.consumer.value-deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        producerProperties(properties);
        properties.put("polaris.kafka.topics.orders-created", ORDER_CREATED_TOPIC);
        properties.put("polaris.kafka.topics.inventory-adjusted", INVENTORY_ADJUSTED_TOPIC);
        properties.put("polaris.kafka.topics.notifications-dlq", "polaris.notifications.dlq");
        properties.put("polaris.notifications.retry.max-attempts", 3);
        properties.put("polaris.notifications.retry.initial-interval", "10ms");
        properties.put("polaris.notifications.retry.multiplier", 2.0);
        return properties;
    }

    private static Map<String, Object> orderProperties(int inventoryGrpcPort) {
        Map<String, Object> properties = commonProperties("order-system-test");
        properties.put("server.port", 0);
        properties.put("spring.datasource.url", orderPostgres.getJdbcUrl());
        properties.put("spring.datasource.username", orderPostgres.getUsername());
        properties.put("spring.datasource.password", orderPostgres.getPassword());
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.open-in-view", "false");
        properties.put("spring.liquibase.enabled", "false");
        properties.put("spring.mvc.problemdetails.enabled", "true");
        producerProperties(properties);
        properties.put("polaris.kafka.topics.order-created", ORDER_CREATED_TOPIC);
        properties.put("polaris.inventory.grpc.host", "localhost");
        properties.put("polaris.inventory.grpc.port", inventoryGrpcPort);
        properties.put("polaris.inventory.grpc.deadline", "5s");
        properties.put("grpc.client.inventory-service.address", "static://localhost:" + inventoryGrpcPort);
        properties.put("grpc.client.inventory-service.negotiation-type", "plaintext");
        return properties;
    }

    private static Map<String, Object> gatewayProperties(int orderPort) {
        Map<String, Object> properties = commonProperties("gateway-system-test");
        properties.put("spring.cloud.gateway.server.webflux.enabled", "true");
        // Inventory adds shaded gRPC to this harness; production gateway does not expose JSON-to-gRPC proxying.
        properties.put("spring.cloud.gateway.server.webflux.filter.json-to-grpc.enabled", "false");
        // Other services put JDBC/JPA on this test-only shared classpath; the real gateway has neither.
        properties.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration"));
        properties.put("server.port", 0);
        properties.put("polaris.gateway.rate-limit.enabled", "false");
        properties.put("spring.cloud.gateway.server.webflux.routes[0].id", "order-create");
        properties.put("spring.cloud.gateway.server.webflux.routes[0].uri", "http://localhost:" + orderPort);
        properties.put("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", "Path=/api/v1/orders");
        properties.put("spring.cloud.gateway.server.webflux.routes[0].predicates[1]", "Method=POST");
        properties.put("spring.cloud.gateway.server.webflux.routes[1].id", "order-read");
        properties.put("spring.cloud.gateway.server.webflux.routes[1].uri", "http://localhost:" + orderPort);
        properties.put("spring.cloud.gateway.server.webflux.routes[1].predicates[0]", "Path=/api/v1/orders/{orderId}");
        properties.put("spring.cloud.gateway.server.webflux.routes[1].predicates[1]", "Method=GET");
        properties.put("spring.cloud.gateway.server.webflux.routes[2].id", "order-public-api");
        properties.put("spring.cloud.gateway.server.webflux.routes[2].uri", "http://localhost:" + orderPort);
        properties.put("spring.cloud.gateway.server.webflux.routes[2].predicates[0]", "Path=/api/v1/orders/**");
        return properties;
    }

    private static Map<String, Object> commonProperties(String applicationName) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.name", "contract-test-empty");
        properties.put("spring.application.name", applicationName);
        properties.put("spring.jmx.enabled", "false");
        // Reproduce each application's runtime boundary despite this harness's combined test classpath.
        properties.put("spring.cloud.gateway.server.webflux.enabled", "false");
        properties.put("spring.cloud.gateway.server.webflux.redis.enabled", "false");
        properties.put("grpc.server.port", "-1");
        properties.put("spring.kafka.bootstrap-servers", kafka.getBootstrapServers());
        properties.put("management.tracing.enabled", "false");
        properties.put("management.otlp.tracing.export.enabled", "false");
        return properties;
    }

    private static void producerProperties(Map<String, Object> properties) {
        properties.put("spring.kafka.producer.key-serializer", "org.apache.kafka.common.serialization.StringSerializer");
        properties.put("spring.kafka.producer.value-serializer", "org.springframework.kafka.support.serializer.JsonSerializer");
        properties.put("spring.kafka.producer.properties.spring.json.add.type.headers", "false");
    }

    private static void initializeDatabase(PostgreSQLContainer<?> postgres, String service) throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
        ResourceDatabasePopulator migrations = new ResourceDatabasePopulator();
        Path changes = REPOSITORY_ROOT.resolve(service).resolve("src/main/resources/db/changelog/changes");
        try (var scripts = Files.list(changes)) {
            scripts.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .map(FileSystemResource::new)
                    .forEach(migrations::addScript);
        }
        migrations.execute(dataSource);
    }

    private static int webPort(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Class<?> gatewayApplicationClass() throws ClassNotFoundException {
        return Class.forName("io.polaris.gateway.GatewayApplication");
    }

    private static PostgreSQLContainer<?> postgres(String database, String username) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database)
                .withUsername(username)
                .withPassword(username);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("order-service"))
                    && Files.isDirectory(candidate.resolve("inventory-service"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate Polaris repository root");
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }

    static class RecordingNotificationHandler implements NotificationHandler {
        private final AtomicReference<OrderCreatedEvent> order = new AtomicReference<>();
        private final AtomicReference<InventoryAdjustedEvent> inventory = new AtomicReference<>();

        @Override
        public void handle(OrderCreatedEvent event) {
            order.set(event);
        }

        @Override
        public void handle(InventoryAdjustedEvent event) {
            inventory.set(event);
        }

        OrderCreatedEvent order() {
            return order.get();
        }

        InventoryAdjustedEvent inventory() {
            return inventory.get();
        }

        boolean awaitOrder(UUID orderId, Duration timeout) throws InterruptedException {
            return await(() -> order.get() != null && orderId.equals(order.get().orderId()), timeout);
        }

        boolean awaitInventory(UUID orderId, Duration timeout) throws InterruptedException {
            return await(() -> inventory.get() != null && orderId.equals(inventory.get().orderId()), timeout);
        }

        private boolean await(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        }
    }

    static class NotificationRecordingConfiguration {
        @Bean
        @Primary
        RecordingNotificationHandler recordingNotificationHandler() {
            return new RecordingNotificationHandler();
        }
    }

    static class GatewayTestConfiguration {
        @Bean
        NettyReactiveWebServerFactory gatewayWebServerFactory() {
            // The combined classpath also contains order-service's Tomcat; production gateway uses Netty.
            return new NettyReactiveWebServerFactory();
        }
    }

    static class TestJwtConfiguration {
        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return token -> Mono.just(jwt(token));
        }

        @Bean
        JwtDecoder servletTestJwtDecoder() {
            return OrderLifecycleSystemIntegrationTest::jwt;
        }
    }

    private static Jwt jwt(String token) {
        return Jwt.withTokenValue(token)
                .header("alg", "test")
                .subject(token)
                .claim("scope", "orders:write orders:read")
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
