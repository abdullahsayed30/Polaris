package io.polaris.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import io.polaris.inventory.grpc.InventoryProto;
import io.polaris.notification.adapter.out.messaging.NotificationDeadLetterEvent;
import io.polaris.order.adapter.in.web.OrderController;
import io.polaris.order.adapter.in.web.OrderItemRequest;
import io.polaris.order.adapter.in.web.OrderItemResponse;
import io.polaris.order.adapter.in.web.OrderResponse;
import io.polaris.order.adapter.in.web.PlaceOrderRequest;
import io.polaris.order.application.domain.model.OrderStatus;
import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

class ContractDriftTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void openApiMatchesPublicControllerAndDtoSurface() throws Exception {
        JsonNode contract = yaml("contracts/openapi/order-api-v1.yaml");

        assertThat(contract.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(fieldNames(contract.path("paths")))
                .containsExactlyInAnyOrder("/api/v1/orders", "/api/v1/orders/{id}");
        assertThat(contract.at("/paths/~1api~1v1~1orders/post/operationId").asText()).isEqualTo("placeOrder");
        assertThat(contract.at("/paths/~1api~1v1~1orders~1{id}/get/operationId").asText()).isEqualTo("getOrder");

        RequestMapping basePath = OrderController.class.getAnnotation(RequestMapping.class);
        assertThat(basePath.value()).containsExactly("/api/v1/orders");
        assertThat(Arrays.stream(OrderController.class.getMethods())
                .filter(method -> method.getName().equals("placeOrder"))
                .findFirst()).isPresent().get().extracting(method -> method.getAnnotation(PostMapping.class)).isNotNull();
        assertThat(Arrays.stream(OrderController.class.getMethods())
                .filter(method -> method.getName().equals("getOrder"))
                .findFirst()).isPresent().get()
                .extracting(method -> method.getAnnotation(GetMapping.class).value())
                .isEqualTo(new String[]{"/{id}"});

        assertSchemaProperties(contract, "PlaceOrderRequest", PlaceOrderRequest.class);
        assertSchemaProperties(contract, "OrderItemRequest", OrderItemRequest.class);
        assertSchemaProperties(contract, "Order", OrderResponse.class);
        assertSchemaProperties(contract, "OrderItem", OrderItemResponse.class);
        assertThat(textValues(contract.at("/components/schemas/OrderStatus/enum")))
                .containsExactly(Arrays.stream(OrderStatus.values()).map(Enum::name).toArray(String[]::new));

        assertThat(contract.at("/components/schemas/PlaceOrderRequest/properties/items/minItems").asInt()).isOne();
        assertThat(contract.at("/components/schemas/OrderItemRequest/properties/sku/maxLength").asInt()).isEqualTo(128);
        assertThat(contract.at("/components/schemas/OrderItemRequest/properties/quantity/minimum").asInt()).isOne();
        assertThat(contract.at("/components/schemas/OrderItemRequest/properties/unitPrice/minimum").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.01"));

        assertThat(fieldNames(contract.at("/paths/~1api~1v1~1orders/post/responses")))
                .containsExactlyInAnyOrder("201", "400", "401", "403", "409", "429", "503");
        assertThat(fieldNames(contract.at("/paths/~1api~1v1~1orders~1{id}/get/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "429");
    }

    @Test
    void asyncApiReferencesSchemasThatMatchJavaEventRecords() throws Exception {
        JsonNode contract = yaml("contracts/asyncapi/polaris-events-v1.yaml");

        assertThat(contract.path("asyncapi").asText()).isEqualTo("2.6.0");
        assertThat(fieldNames(contract.path("channels"))).containsExactlyInAnyOrder(
                "polaris.orders.created",
                "polaris.inventory.adjusted",
                "polaris.notifications.dlq");
        assertMessageReference(contract, "polaris.orders.created", "OrderCreated", "../events/v1/order-created.schema.json");
        assertMessageReference(contract, "polaris.inventory.adjusted", "InventoryAdjusted", "../events/v1/inventory-adjusted.schema.json");
        assertMessageReference(contract, "polaris.notifications.dlq", "NotificationDeadLetter",
                "../events/v1/notification-dead-letter.schema.json");

        JsonNode orderSchema = json("contracts/events/v1/order-created.schema.json");
        JsonNode inventorySchema = json("contracts/events/v1/inventory-adjusted.schema.json");
        JsonNode deadLetterSchema = json("contracts/events/v1/notification-dead-letter.schema.json");
        assertRecordSchema(orderSchema, OrderCreatedEvent.class);
        assertRecordSchema(orderSchema.path("$defs").path("metadata"), EventMetadata.class);
        assertRecordSchema(orderSchema.path("$defs").path("item"), OrderCreatedEvent.Item.class);
        assertRecordSchema(inventorySchema, InventoryAdjustedEvent.class);
        assertRecordSchema(inventorySchema.path("$defs").path("metadata"), EventMetadata.class);
        assertRecordSchema(inventorySchema.path("$defs").path("item"), InventoryAdjustedEvent.Item.class);
        assertRecordSchema(deadLetterSchema, NotificationDeadLetterEvent.class);
        assertRecordSchema(deadLetterSchema.path("$defs").path("metadata"), EventMetadata.class);

        assertSerializedProperties(orderSchema, new OrderCreatedEvent(
                EventMetadata.initial(1, Instant.parse("2026-01-01T00:00:00Z"), "contract-test"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderCreatedEvent.OrderStatus.CONFIRMED,
                List.of(new OrderCreatedEvent.Item(UUID.randomUUID(), "SKU-1", 1, new BigDecimal("1.25"))),
                Instant.parse("2026-01-01T00:00:00Z")));
        assertSerializedProperties(inventorySchema, new InventoryAdjustedEvent(
                EventMetadata.initial(1, Instant.parse("2026-01-01T00:00:01Z"), "contract-test"),
                UUID.randomUUID(),
                List.of(new InventoryAdjustedEvent.Item("SKU-1", -1, 9)),
                Instant.parse("2026-01-01T00:00:01Z")));
        assertSerializedProperties(deadLetterSchema, new NotificationDeadLetterEvent(
                EventMetadata.initial(1, Instant.parse("2026-01-01T00:00:02Z"), "contract-test"),
                "polaris.orders.created",
                0,
                42,
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                1,
                "OrderCreated",
                "{}",
                IllegalStateException.class.getName(),
                "failure",
                Instant.parse("2026-01-01T00:00:02Z")));
    }

    @Test
    void protobufV1RetainsEveryPublishedSymbolAndWireNumber() throws Exception {
        JsonNode baseline = json("contracts/protobuf/polaris.inventory.v1.json");
        FileDescriptor descriptor = InventoryProto.getDescriptor();

        assertThat(descriptor.getPackage()).isEqualTo(baseline.path("package").asText());
        baseline.path("messages").properties().forEach(message -> assertMessage(descriptor, message.getKey(), message.getValue()));
        baseline.path("enums").properties().forEach(entry -> assertEnum(descriptor, entry.getKey(), entry.getValue()));
        baseline.path("services").properties().forEach(entry -> assertService(descriptor, entry.getKey(), entry.getValue()));
    }

    @Test
    void documentedHttpRoutesAndKafkaTopicsMatchRuntimeConfiguration() throws Exception {
        JsonNode gateway = yamlFile("gateway/src/main/resources/application.yml");
        JsonNode order = yamlFile("order-service/src/main/resources/application.yml");
        JsonNode inventory = yamlFile("inventory-service/src/main/resources/application.yml");
        JsonNode notification = yamlFile("notification-service/src/main/resources/application.yml");

        JsonNode routes = gateway.at("/spring/cloud/gateway/server/webflux/routes");
        assertThat(routes).hasSize(3);
        assertThat(routes.path(0).path("predicates").path(0).asText()).isEqualTo("Path=/api/v1/orders");
        assertThat(routes.path(1).path("predicates").path(0).asText()).isEqualTo("Path=/api/v1/orders/{orderId}");
        assertThat(order.at("/polaris/kafka/topics/order-created").asText()).isEqualTo("polaris.orders.created");
        assertThat(inventory.at("/polaris/kafka/topics/orders-created").asText()).isEqualTo("polaris.orders.created");
        assertThat(inventory.at("/polaris/kafka/topics/inventory-adjusted").asText())
                .isEqualTo("polaris.inventory.adjusted");
        assertThat(notification.at("/polaris/kafka/topics/orders-created").asText())
                .isEqualTo("polaris.orders.created");
        assertThat(notification.at("/polaris/kafka/topics/inventory-adjusted").asText())
                .isEqualTo("polaris.inventory.adjusted");
        assertThat(notification.at("/polaris/kafka/topics/notifications-dlq").asText())
                .isEqualTo("polaris.notifications.dlq");
    }

    private void assertSchemaProperties(JsonNode openApi, String schemaName, Class<?> recordType) {
        JsonNode schema = openApi.path("components").path("schemas").path(schemaName);
        assertRecordProperties(schema, recordType);
    }

    private void assertRecordSchema(JsonNode schema, Class<?> recordType) {
        assertRecordProperties(schema, recordType);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    private void assertRecordProperties(JsonNode schema, Class<?> recordType) {
        Set<String> components = Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(fieldNames(schema.path("properties"))).containsExactlyInAnyOrderElementsOf(components);
        assertThat(textValues(schema.path("required"))).containsExactlyInAnyOrderElementsOf(components);
    }

    private void assertSerializedProperties(JsonNode schema, Object event) {
        JsonNode serialized = JSON.valueToTree(event);
        assertThat(fieldNames(serialized)).containsExactlyInAnyOrderElementsOf(fieldNames(schema.path("properties")));
        assertThat(textValues(schema.path("required"))).allMatch(serialized::hasNonNull);
    }

    private void assertMessageReference(JsonNode contract, String channel, String message, String payloadReference) {
        String escapedChannel = channel.replace("~", "~0").replace("/", "~1");
        assertThat(contract.at("/channels/" + escapedChannel + "/publish/message/$ref").asText())
                .isEqualTo("#/components/messages/" + message);
        assertThat(contract.at("/components/messages/" + message + "/payload/$ref").asText())
                .isEqualTo(payloadReference);
    }

    private void assertMessage(FileDescriptor file, String name, JsonNode expected) {
        Descriptor message = file.findMessageTypeByName(name);
        assertThat(message).as("protobuf message %s", name).isNotNull();
        expected.properties().forEach(entry -> {
            FieldDescriptor field = message.findFieldByName(entry.getKey());
            JsonNode definition = entry.getValue();
            assertThat(field).as("field %s.%s", name, entry.getKey()).isNotNull();
            assertThat(field.getNumber()).isEqualTo(definition.path("number").asInt());
            assertThat(field.getType().name()).isEqualTo(definition.path("type").asText());
            assertThat(field.isRepeated()).isEqualTo(definition.path("repeated").asBoolean());
            if (definition.has("typeName")) {
                String actualTypeName = field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                        ? field.getMessageType().getName()
                        : field.getEnumType().getName();
                assertThat(actualTypeName).isEqualTo(definition.path("typeName").asText());
            }
        });
    }

    private void assertEnum(FileDescriptor file, String name, JsonNode expected) {
        EnumDescriptor enumDescriptor = file.findEnumTypeByName(name);
        assertThat(enumDescriptor).as("protobuf enum %s", name).isNotNull();
        expected.properties().forEach(entry -> {
            assertThat(enumDescriptor.findValueByName(entry.getKey()))
                    .as("enum value %s.%s", name, entry.getKey())
                    .isNotNull()
                    .extracting(value -> value.getNumber())
                    .isEqualTo(entry.getValue().asInt());
        });
    }

    private void assertService(FileDescriptor file, String name, JsonNode expected) {
        ServiceDescriptor service = file.findServiceByName(name);
        assertThat(service).as("protobuf service %s", name).isNotNull();
        expected.path("methods").properties().forEach(entry -> {
            MethodDescriptor method = service.findMethodByName(entry.getKey());
            JsonNode definition = entry.getValue();
            assertThat(method).as("RPC %s.%s", name, entry.getKey()).isNotNull();
            assertThat(method.getInputType().getName()).isEqualTo(definition.path("input").asText());
            assertThat(method.getOutputType().getName()).isEqualTo(definition.path("output").asText());
            assertThat(method.isClientStreaming()).isEqualTo(definition.path("clientStreaming").asBoolean());
            assertThat(method.isServerStreaming()).isEqualTo(definition.path("serverStreaming").asBoolean());
        });
    }

    private static Set<String> fieldNames(JsonNode node) {
        Iterator<String> names = node.fieldNames();
        Set<String> result = new LinkedHashSet<>();
        names.forEachRemaining(result::add);
        return result;
    }

    private static List<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static JsonNode yaml(String resource) throws IOException {
        return read(YAML, resource);
    }

    private static JsonNode json(String resource) throws IOException {
        return read(JSON, resource);
    }

    private static JsonNode yamlFile(String relativePath) throws IOException {
        try (InputStream input = Files.newInputStream(repositoryRoot().resolve(relativePath))) {
            return YAML.readTree(input);
        }
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

    private static JsonNode read(ObjectMapper mapper, String resource) throws IOException {
        try (InputStream input = ContractDriftTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("classpath resource %s", resource).isNotNull();
            return mapper.readTree(input);
        }
    }
}
