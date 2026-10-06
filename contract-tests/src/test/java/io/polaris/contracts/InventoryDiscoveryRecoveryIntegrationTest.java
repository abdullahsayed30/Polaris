package io.polaris.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class InventoryDiscoveryRecoveryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> orderPostgres = postgres("polaris_orders", "polaris_order");

    @Container
    static final PostgreSQLContainer<?> inventoryPostgres = postgres("polaris_inventory", "polaris_inventory");

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void sameRunningOrderRecoversWhenInitiallyUnresolvableInventoryReturns() throws Exception {
        Path root = repositoryRoot();
        initializeDatabase(orderPostgres, root.resolve("order-service"));
        initializeDatabase(inventoryPostgres, root.resolve("inventory-service"));
        Properties fixture = new Properties();
        databaseProperties(fixture, "order", orderPostgres);
        databaseProperties(fixture, "inventory", inventoryPostgres);
        fixture.setProperty("kafka", kafka.getBootstrapServers());
        Path propertiesFile = temporaryDirectory.resolve("fixture.properties");
        try (var output = Files.newOutputStream(propertiesFile)) {
            fixture.store(output, "Isolated discovery recovery fixture");
        }
        Path hosts = temporaryDirectory.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        Path log = temporaryDirectory.resolve("scenario.log");

        // InetAddress selects its resolver at first use. A child JVM isolates the hosts-file
        // fixture from Testcontainers and the rest of the test suite, retaining normal DNS caching.
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Djdk.net.hosts.file=" + hosts,
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                InventoryDiscoveryRecoveryScenario.class.getName(),
                root.toString(), propertiesFile.toString(), hosts.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            boolean completed = process.waitFor(180, TimeUnit.SECONDS);
            assertThat(completed).withFailMessage("Discovery scenario timed out:%n%s", Files.readString(log)).isTrue();
            assertThat(process.exitValue()).withFailMessage("Discovery scenario failed:%n%s", Files.readString(log)).isZero();
            assertThat(Files.readString(log)).contains("DISCOVERY_RECOVERY_VERIFIED");
            Files.readString(log).lines().filter(line -> line.startsWith("DISCOVERY_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static PostgreSQLContainer<?> postgres(String database, String username) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database)
                .withUsername(username)
                .withPassword(username);
    }

    private static void databaseProperties(Properties fixture, String prefix, PostgreSQLContainer<?> postgres) {
        fixture.setProperty(prefix + ".url", postgres.getJdbcUrl());
        fixture.setProperty(prefix + ".username", postgres.getUsername());
        fixture.setProperty(prefix + ".password", postgres.getPassword());
    }

    private static void initializeDatabase(PostgreSQLContainer<?> postgres, Path service) throws IOException {
        ResourceDatabasePopulator migrations = new ResourceDatabasePopulator();
        try (var scripts = Files.list(service.resolve("src/main/resources/db/changelog/changes"))) {
            scripts.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .map(FileSystemResource::new)
                    .forEach(migrations::addScript);
        }
        migrations.execute(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("order-service")) && Files.isDirectory(candidate.resolve("inventory-service"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate Polaris repository root");
    }
}
