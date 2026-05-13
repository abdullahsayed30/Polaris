package io.polaris.order.config;

import org.slf4j.MDC;

import io.grpc.Metadata;

public final class ObservabilityConstants {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "request.id";
    public static final Metadata.Key<String> REQUEST_ID_METADATA_KEY = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    private ObservabilityConstants() {
    }

    public static String currentRequestId() {
        return MDC.get(REQUEST_ID_MDC_KEY);
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
