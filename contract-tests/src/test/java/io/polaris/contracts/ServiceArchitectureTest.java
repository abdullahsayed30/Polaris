package io.polaris.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

/** Source guards for ADRs 0015, 0019 and 0021; these are not a full semantic architecture proof. */
class ServiceArchitectureTest {
    private static final List<String> MODULES = List.of(
            "gateway", "order-service", "inventory-service", "notification-service", "shared");
    private static final Set<String> BOOKKEEPING_TYPES = Set.of(
            "OutboxEvent", "OutboxStatus", "InboxEvent", "InboxStatus");
    private static final List<String> TRANSPORT_PREFIXES = List.of(
            "org.apache.kafka.", "org.springframework.kafka.", "io.grpc.", "com.google.protobuf.",
            "org.springframework.web.", "org.springframework.http.", "java.net.http.");

    @Test
    void mainSourcesRespectServicePackageAndDependencyRules() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (String module : MODULES) {
            Path sourceRoot = root.resolve(module).resolve("src/main/java");
            try (var files = Files.walk(sourceRoot)) {
                List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
                assertThat(sources).as("Main sources in %s", module).isNotEmpty();
                for (Path source : sources) {
                    for (String violation : violations(Files.readString(source))) {
                        violations.add(root.relativize(source) + ": " + violation);
                    }
                }
            }
        }
        assertThat(violations).as("Architecture rules in AGENTS.md and the service architecture standard").isEmpty();
    }

    @Test
    void catchesImportsAndQualifiedTransportReferencesButIgnoresCommentsAndStrings() throws IOException {
        assertThat(violations("""
                package io.polaris.order.application.domain.service;
                import io.polaris.order.adapter.out.persistence.OrderEventOutbox;
                class Bad {
                    io.grpc.Channel channel;
                    // io.polaris.order.adapter.in.web.OrderController is only a comment.
                    String explanation = "org.springframework.kafka.core.KafkaTemplate";
                }
                """))
                .anyMatch(message -> message.contains("OrderEventOutbox"))
                .anyMatch(message -> message.contains("io.grpc.Channel"))
                .noneMatch(message -> message.contains("OrderController") || message.contains("KafkaTemplate"));
    }

    @Test
    void catchesDomainInfrastructureAndSharedFrameworkDependencies() throws IOException {
        assertThat(violations("""
                package io.polaris.order.application.domain.model;
                import io.polaris.order.application.port.out.InventoryClient;
                import org.springframework.stereotype.Component;
                class Bad { jakarta.persistence.EntityManager entityManager; }
                """))
                .anyMatch(message -> message.contains("InventoryClient"))
                .anyMatch(message -> message.contains("Component"))
                .anyMatch(message -> message.contains("EntityManager"));
        assertThat(violations("""
                package io.polaris.shared.events;
                record Bad(org.springframework.http.HttpStatus status) {}
                """))
                .anyMatch(message -> message.contains("Shared contract"));
    }

    @Test
    void catchesMisplacedRepositoriesBookkeepingAndConfigurationClasses() throws IOException {
        assertThat(violations("""
                package io.polaris.order.adapter.out.messaging;
                interface BadRepository extends org.springframework.data.jpa.repository.JpaRepository<Object, String> {}
                @jakarta.persistence.Entity class OutboxEvent {}
                @org.springframework.boot.context.properties.ConfigurationProperties("bad") class BadProperties {}
                """))
                .anyMatch(message -> message.contains("Spring Data repository"))
                .anyMatch(message -> message.contains("Bookkeeping"))
                .anyMatch(message -> message.contains("must live in the composition root"))
                .anyMatch(message -> message.contains("must be a record"));
    }

    @Test
    void catchesListenerTransactionsAndDirectPersistenceAccess() throws IOException {
        assertThat(violations("""
                package io.polaris.notification.adapter.in.messaging;
                import io.polaris.notification.adapter.out.persistence.InboxEventRepository;
                @org.springframework.transaction.annotation.Transactional
                class Bad {
                    @org.springframework.kafka.annotation.KafkaListener(topics = "events")
                    void listen() {}
                }
                """))
                .anyMatch(message -> message.contains("Listener must delegate transactions"))
                .anyMatch(message -> message.contains("Listener must delegate persistence"));
    }

    @Test
    void allowsPureModelsPortsServiceTransactionsAndAdapterPersistence() throws IOException {
        for (String source : List.of(
                """
                        package io.polaris.order.adapter.out.persistence;
                        @jakarta.persistence.Entity class OrderJpaEntity {}
                        """,
                """
                        package io.polaris.order.application.domain.service;
                        import io.polaris.order.application.port.out.InventoryClient;
                        import io.polaris.shared.events.OrderCreatedEvent;
                        @org.springframework.transaction.annotation.Transactional
                        class PlaceOrder { InventoryClient inventory; OrderCreatedEvent event; }
                        """,
                """
                        package io.polaris.order.adapter.out.messaging;
                        @org.springframework.transaction.annotation.Transactional
                        class OutboxPublisher {}
                        """,
                """
                        package io.polaris.order;
                        @org.springframework.boot.context.properties.ConfigurationProperties("valid")
                        record Properties(int batchSize) {}
                        """)) {
            assertThat(violations(source)).as(source).isEmpty();
        }
    }

    @Test
    void rejectsJpaModelsTransportPortsConcreteServiceDependenciesAndCrossServiceImports() throws IOException {
        for (String source : List.of(
                """
                        package io.polaris.order.application.domain.model;
                        @jakarta.persistence.Entity class Order {}
                        """,
                """
                        package io.polaris.order.application.port.out;
                        interface Stock { io.grpc.Channel channel();
                        }
                        """,
                """
                        package io.polaris.order.application.port.in;
                        interface Stock { org.springframework.data.domain.Page page();
                        }
                        """,
                """
                        package io.polaris.order.application.domain.service;
                        class Bad { io.polaris.order.adapter.out.persistence.OrderRepository r;
                        }
                        """,
                """
                        package io.polaris.order.adapter.in.web;
                        class Bad { io.polaris.order.application.domain.service.OrderApplicationService s;
                        }
                        """,
                """
                        package io.polaris.order.application.domain.service;
                        class Bad { io.polaris.order.ReservationRecoveryProperties p;
                        }
                        """,
                """
                        package io.polaris.order.adapter.out.grpc;
                        class Bad { io.polaris.inventory.application.port.in.ReserveStockUseCase s;
                        }
                        """,
                """
                        package io.polaris.order.application.port.in;
                        interface Bad {
                            io.polaris.order.application.domain.service.OrderApplicationService service();
                        }
                        """)) {
            assertThat(violations(source)).as(source).isNotEmpty();
        }
    }

    @Test
    void allowsPurePortsAndGatewayExistingStructure() throws IOException {
        for (String source : List.of(
                """
                        package io.polaris.order.application.domain.model;
                        record Amount(java.math.BigDecimal value) {}
                        """,
                """
                        package io.polaris.order.application.port.in;
                        interface Get { io.polaris.order.application.domain.model.Order get();
                        }
                        """,
                """
                        package io.polaris.order.adapter.out.grpc;
                        class Client { io.polaris.inventory.grpc.ReserveRequest request;
                        }
                        """,
                """
                        package io.polaris.gateway.config;
                        @org.springframework.boot.context.properties.ConfigurationProperties("rate")
                        record Rate(int limit) {}
                        """)) {
            assertThat(violations(source)).as(source).isEmpty();
        }
    }

    private static List<String> violations(String source) throws IOException {
        SourceShape shape = parse(source);
        String root = serviceRoot(shape.packageName);
        boolean businessService = List.of("io.polaris.order", "io.polaris.inventory", "io.polaris.notification").contains(root);
        boolean application = shape.packageName.startsWith(root + ".application.");
        boolean model = shape.packageName.startsWith(root + ".application.domain.model");
        boolean port = shape.packageName.startsWith(root + ".application.port.");
        boolean inbound = shape.packageName.startsWith(root + ".adapter.in.");
        boolean persistence = shape.packageName.equals(root + ".adapter.out.persistence");
        Set<String> failures = new LinkedHashSet<>();
        if (businessService && !shape.packageName.equals(root)
                && !List.of(".application.domain.model", ".application.domain.service", ".application.port.in",
                        ".application.port.out", ".adapter.in.", ".adapter.out.").stream()
                        .anyMatch(part -> shape.packageName.startsWith(root + part))) {
            failures.add("Package must follow ADR 0021: " + shape.packageName);
        }
        for (String reference : shape.references) {
            if (businessService && application && !allowedApplicationReference(reference, root, model, port)) {
                failures.add((model
                        ? "Domain must be independent of infrastructure: "
                        : "Application must depend on ports, not transport adapters: ") + reference);
            }
            if (businessService && inbound && (reference.startsWith(root + ".application.domain.service.")
                    || reference.startsWith(root + ".adapter.out."))) {
                failures.add("Inbound adapter must depend on an inbound port: " + reference);
            }
            if (reference.startsWith("io.polaris.") && !reference.startsWith(root + ".")
                    && !reference.equals(root) && !reference.startsWith("io.polaris.shared.")
                    && !reference.startsWith("io.polaris.inventory.grpc.")) {
                failures.add("Runtime service must not depend on another service implementation: " + reference);
            }
            if ((shape.packageName.equals("io.polaris.shared") || shape.packageName.startsWith("io.polaris.shared."))
                    && sharedInfrastructure(reference)) {
                failures.add("Shared contract must be framework and service independent: " + reference);
            }
            if (springDataRepository(reference) && !persistence) {
                failures.add("Spring Data repository must live in persistence: " + reference);
            }
            if (shape.annotations.contains("KafkaListener") && inPackage(reference, "persistence")) {
                failures.add("Listener must delegate persistence to an application use case: " + reference);
            }
        }
        for (ClassTree type : shape.types) {
            String name = type.getSimpleName().toString();
            boolean bookkeeping = BOOKKEEPING_TYPES.contains(name)
                    || (hasAnnotation(type, "Entity") && (name.startsWith("Outbox") || name.startsWith("Inbox")));
            if (bookkeeping && !persistence) {
                failures.add("Bookkeeping model must live in persistence: " + name);
            }
            if (businessService && hasAnnotation(type, "Entity") && !persistence) {
                failures.add("JPA entity must live in outbound persistence: " + name);
            }
            if (hasAnnotation(type, "ConfigurationProperties")) {
                boolean configurationPackage = businessService
                        ? shape.packageName.equals(root)
                        : inPackage(shape.packageName, "config");
                if (!configurationPackage) {
                    failures.add("Configuration properties must live in the composition root: " + name);
                }
                if (type.getKind() != Tree.Kind.RECORD) {
                    failures.add("Configuration properties must be a record: " + name);
                }
            }
        }
        if (shape.annotations.contains("KafkaListener") && shape.annotations.contains("Transactional")) {
            failures.add("Listener must delegate transactions to an application use case");
        }
        return List.copyOf(failures);
    }

    private static String serviceRoot(String packageName) {
        String[] segments = packageName.split("\\.");
        return String.join(".", segments[0], segments[1], segments[2]);
    }

    private static boolean allowedApplicationReference(String reference, String root, boolean model, boolean port) {
        if (reference.startsWith("java.")) {
            return !reference.startsWith("java.sql.") && !reference.startsWith("java.net.");
        }
        if (reference.startsWith(root + ".application.domain.model") || reference.startsWith("io.polaris.shared.")) {
            return true;
        }
        if (model) {
            return false;
        }
        if (reference.startsWith(root + ".application.port.")) {
            return true;
        }
        if (port) {
            return false;
        }
        return reference.startsWith(root + ".application.domain.service")
                || reference.equals("org.springframework.stereotype.Service")
                || reference.equals("org.springframework.stereotype.Component")
                || reference.startsWith("org.springframework.transaction.annotation.")
                || reference.equals("org.slf4j.Logger") || reference.equals("org.slf4j.LoggerFactory");
    }

    private static boolean sharedInfrastructure(String reference) {
        return !reference.startsWith("java.") && !reference.equals("io.polaris.shared")
                && !reference.startsWith("io.polaris.shared.");
    }

    private static boolean springDataRepository(String reference) {
        return reference.startsWith("org.springframework.data.")
                && (reference.contains(".repository.") || reference.endsWith("Repository"));
    }

    private static boolean inPackage(String name, String segment) {
        return ("." + name + ".").contains("." + segment + ".");
    }

    private static boolean hasAnnotation(ClassTree type, String name) {
        return type.getModifiers().getAnnotations().stream()
                .anyMatch(annotation -> simpleName(annotation.getAnnotationType().toString()).equals(name));
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static SourceShape parse(String source) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Architecture checks require the project JDK").isNotNull();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///ArchitectureProbe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, List.of(input));
            CompilationUnitTree unit = task.parse().iterator().next();
            assertThat(diagnostics.getDiagnostics()).as("Java source parses for architecture inspection")
                    .noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR);
            SourceShape shape = new SourceShape(unit.getPackageName().toString());
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitImport(ImportTree node, Void unused) {
                    shape.references.add(node.getQualifiedIdentifier().toString());
                    return null;
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    String reference = node.toString();
                    if (List.of("java.", "javax.", "jakarta.", "org.", "com.", "io.").stream()
                            .anyMatch(reference::startsWith)) {
                        shape.references.add(reference);
                        return null;
                    }
                    return super.visitMemberSelect(node, unused);
                }

                @Override
                public Void visitAnnotation(AnnotationTree node, Void unused) {
                    shape.annotations.add(simpleName(node.getAnnotationType().toString()));
                    return super.visitAnnotation(node, unused);
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    shape.types.add(node);
                    return super.visitClass(node, unused);
                }
            }.scan(unit, null);
            return shape;
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("docs/service-architecture-standard.md"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate).as("Repository root containing the architecture standard").isNotNull();
        return candidate;
    }

    private static final class SourceShape {
        private final String packageName;
        private final Set<String> references = new LinkedHashSet<>();
        private final Set<String> annotations = new LinkedHashSet<>();
        private final List<ClassTree> types = new ArrayList<>();

        private SourceShape(String packageName) {
            this.packageName = packageName;
        }
    }
}
