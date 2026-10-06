package io.polaris.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.grpc.Channel;

import io.polaris.inventory.InventoryServiceApplication;
import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.ReserveRequest;
import io.polaris.inventory.grpc.StockItem;
import io.polaris.order.OrderServiceApplication;

/** Real service contexts in the isolated JVM launched by the discovery integration test. */
final class InventoryDiscoveryRecoveryScenario {
    private static final String INVENTORY_HOST = "inventory-discovery-fixture.invalid";
    private static final String SKU = "SKU-DISCOVERY-001";
    private static final String IDEMPOTENCY_KEY = "discovery-recovery";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private InventoryDiscoveryRecoveryScenario() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Properties fixture = new Properties();
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            fixture.load(input);
        }
        Path hosts = Path.of(args[2]);
        assertThatThrownBy(() -> InetAddress.getByName(INVENTORY_HOST)).isInstanceOf(UnknownHostException.class);
        int grpcPort = availablePort();
        Map<String, Object> properties = serviceProperties(root, fixture, "order-service", "order");
        properties.put("server.port", 0);
        properties.put("polaris.inventory.grpc.host", INVENTORY_HOST);
        properties.put("polaris.inventory.grpc.port", grpcPort);
        properties.put("polaris.reservation.recovery.initial-delay", "100ms");
        properties.put("polaris.reservation.recovery.poll-interval", "100ms");
        properties.put("polaris.reservation.recovery.retry-delay", "250ms");

        // Load the actual Order YAML target; do not override its scheme in the test.
        try (ConfigurableApplicationContext order = new SpringApplicationBuilder(
                OrderServiceApplication.class, TestJwtConfiguration.class)
                .web(WebApplicationType.SERVLET).run(arguments(properties))) {
            int httpPort = ((WebServerApplicationContext) order).getWebServer().getPort();
            JdbcTemplate orders = new JdbcTemplate(order.getBean(DataSource.class));
            UUID customerId = UUID.randomUUID();
            HttpRequest request = orderRequest(httpPort, customerId);
            InventoryServiceGrpc.InventoryServiceBlockingStub stub = order.getBean(
                    InventoryServiceGrpc.InventoryServiceBlockingStub.class);
            Channel channel = stub.getChannel();
            HttpResponse<String> failed = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(JSON.readTree(failed.body()).path("title").asText()).isEqualTo("Inventory unavailable");
            Map<String, Object> intent = orders.queryForMap("SELECT id, customer_id, status FROM orders");
            UUID orderId = (UUID) intent.get("id");
            assertThat(intent).containsEntry("customer_id", customerId).containsEntry("status", "PENDING");
            Map<String, Object> binding = orders.queryForMap(
                    "SELECT order_id, customer_id, idempotency_key, request_hash, status FROM order_requests");
            assertThat(binding).containsEntry("order_id", orderId).containsEntry("customer_id", customerId)
                    .containsEntry("idempotency_key", IDEMPOTENCY_KEY).containsEntry("status", "PROCESSING");
            assertThat(count(orders, "SELECT count(*) FROM outbox_events")).isZero();
            System.out.println("DISCOVERY_INITIAL_FAILURE: HTTP 503; committed PENDING intent; orderId=" + orderId);

            Map<String, Object> inventoryProperties = serviceProperties(root, fixture, "inventory-service", "inventory");
            inventoryProperties.put("grpc.server.port", grpcPort);
            inventoryProperties.put("polaris.inventory.grpc.port", grpcPort);
            // Seed before opening DNS, so scheduled recovery cannot race stock initialization.
            try (ConfigurableApplicationContext inventory = new SpringApplicationBuilder(InventoryServiceApplication.class)
                    .web(WebApplicationType.NONE).run(arguments(inventoryProperties))) {
                JdbcTemplate stock = new JdbcTemplate(inventory.getBean(DataSource.class));
                stock.update("""
                        INSERT INTO inventory_items (id, sku, available_quantity, created_at, updated_at, version)
                        VALUES (?, ?, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                        """, UUID.randomUUID(), SKU);
                Files.writeString(hosts, "127.0.0.1 localhost\n127.0.0.1 " + INVENTORY_HOST + "\n");
                await(() -> hostnameAvailable(), Duration.ofSeconds(45));
                await(() -> "CONFIRMED".equals(orders.queryForObject(
                        "SELECT status FROM orders WHERE id = ?", String.class, orderId)), Duration.ofSeconds(60));

                // No refresh/resetConnectBackoff, context recreation or channel replacement.
                assertThat(order.isActive()).isTrue();
                assertThat(order.getBean(InventoryServiceGrpc.InventoryServiceBlockingStub.class).getChannel()).isSameAs(channel);
                HttpResponse<String> replay = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(replay.statusCode()).isEqualTo(201);
                assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
                JsonNode body = JSON.readTree(replay.body());
                assertThat(body.path("id").asText()).isEqualTo(orderId.toString());
                assertThat(body.path("customerId").asText()).isEqualTo(customerId.toString());
                assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
                Map<String, Object> completedBinding = new LinkedHashMap<>(binding);
                completedBinding.put("status", "COMPLETED");
                assertThat(orders.queryForMap(
                        "SELECT order_id, customer_id, idempotency_key, request_hash, status FROM order_requests"))
                        .containsExactlyInAnyOrderEntriesOf(completedBinding);

                ReserveRequest duplicate = ReserveRequest.newBuilder().setOrderId(orderId.toString())
                        .addItems(StockItem.newBuilder().setSku(SKU).setQuantity(2)).build();
                assertThat(stub.withDeadlineAfter(2, TimeUnit.SECONDS).reserveStock(duplicate).getReserved()).isTrue();
                assertThat(count(stock, "SELECT count(*) FROM inventory_reservations")).isEqualTo(1);
                assertThat(stock.queryForMap("SELECT order_id, status FROM inventory_reservations"))
                        .containsEntry("order_id", orderId).containsEntry("status", "RESERVED");
                assertThat(stock.queryForMap("SELECT requested_quantity, reserved_quantity FROM inventory_reservation_lines"))
                        .containsEntry("requested_quantity", 2).containsEntry("reserved_quantity", 2);
                assertThat(stock.queryForMap("SELECT available_quantity, version FROM inventory_items WHERE sku = ?", SKU))
                        .containsEntry("available_quantity", 8).containsEntry("version", 1L);
                assertThat(count(orders, "SELECT count(*) FROM orders")).isEqualTo(1);
                assertThat(count(orders, "SELECT count(*) FROM order_requests")).isEqualTo(1);
                assertOutbox(orders, orderId);
                assertOutbox(stock, orderId);
                System.out.println("DISCOVERY_RECOVERY_VERIFIED: same Order context/channel; orderId=" + orderId
                        + "; one reservation; stock 10 -> 8 once; one published outbox event per service");
            }
        }
    }

    private static boolean hostnameAvailable() {
        try {
            return InetAddress.getByName(INVENTORY_HOST).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static void assertOutbox(JdbcTemplate database, UUID orderId) throws InterruptedException {
        await(() -> count(database, "SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'") == 1,
                Duration.ofSeconds(30));
        assertThat(count(database, "SELECT count(*) FROM outbox_events")).isEqualTo(1);
        assertThat(database.queryForObject("SELECT aggregate_id FROM outbox_events", String.class)).isEqualTo(orderId.toString());
    }

    private static long count(JdbcTemplate database, String sql) {
        return database.queryForObject(sql, Long.class);
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Discovery recovery condition was not met within " + timeout);
    }

    private static HttpRequest orderRequest(int port, UUID customerId) {
        return HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + customerId)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Request-Id", "discovery-recovery")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"items": [{"sku": "SKU-DISCOVERY-001", "quantity": 2, "unitPrice": 19.99}]}
                        """)).build();
    }

    private static Map<String, Object> serviceProperties(Path root, Properties fixture, String service, String database) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.location", root.resolve(service + "/src/main/resources/application.yml").toUri());
        properties.put("spring.datasource.url", fixture.getProperty(database + ".url"));
        properties.put("spring.datasource.username", fixture.getProperty(database + ".username"));
        properties.put("spring.datasource.password", fixture.getProperty(database + ".password"));
        properties.put("spring.liquibase.enabled", "false");
        properties.put("spring.kafka.bootstrap-servers", fixture.getProperty("kafka"));
        properties.put("spring.kafka.listener.auto-startup", "false");
        properties.put("spring.jmx.enabled", "false");
        // The test-only harness has both services and Gateway on its classpath.
        properties.put("spring.cloud.gateway.server.webflux.enabled", "false");
        properties.put("spring.cloud.gateway.server.webflux.redis.enabled", "false");
        properties.put("grpc.server.port", "-1");
        properties.put("management.tracing.enabled", "false");
        properties.put("management.otlp.tracing.export.enabled", "false");
        return properties;
    }

    private static String[] arguments(Map<String, Object> properties) {
        return properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static class TestJwtConfiguration {
        @Bean
        JwtDecoder servletTestJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "test").subject(token)
                    .claim("scope", "orders:write orders:read")
                    .issuedAt(Instant.now().minusSeconds(30)).expiresAt(Instant.now().plusSeconds(300)).build();
        }
    }
}
