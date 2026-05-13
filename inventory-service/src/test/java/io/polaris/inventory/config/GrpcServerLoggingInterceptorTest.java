package io.polaris.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

class GrpcServerLoggingInterceptorTest {
    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void exposesRequestIdInMdcForServerCallAndRestoresPreviousValue() {
        MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, "previous");
        Metadata headers = new Metadata();
        headers.put(ObservabilityConstants.REQUEST_ID_METADATA_KEY, "request-123");
        AtomicReference<String> mdcAtStart = new AtomicReference<>();
        AtomicReference<String> mdcAtHalfClose = new AtomicReference<>();
        AtomicReference<String> mdcAtClose = new AtomicReference<>();
        RecordingServerCall call = new RecordingServerCall(mdcAtClose);

        ServerCall.Listener<String> listener = new GrpcServerLoggingInterceptor()
                .interceptCall(call, headers, handler(mdcAtStart, mdcAtHalfClose));

        listener.onHalfClose();

        assertThat(mdcAtStart).hasValue("request-123");
        assertThat(mdcAtHalfClose).hasValue("request-123");
        assertThat(mdcAtClose).hasValue("request-123");
        assertThat(MDC.get(ObservabilityConstants.REQUEST_ID_MDC_KEY)).isEqualTo("previous");
    }

    private ServerCallHandler<String, String> handler(
            AtomicReference<String> mdcAtStart,
            AtomicReference<String> mdcAtHalfClose) {
        return (call, headers) -> {
            mdcAtStart.set(MDC.get(ObservabilityConstants.REQUEST_ID_MDC_KEY));
            return new ServerCall.Listener<>() {
                @Override
                public void onHalfClose() {
                    mdcAtHalfClose.set(MDC.get(ObservabilityConstants.REQUEST_ID_MDC_KEY));
                    call.close(Status.OK, new Metadata());
                }
            };
        };
    }

    private static final class RecordingServerCall extends ServerCall<String, String> {
        private final AtomicReference<String> mdcAtClose;

        private RecordingServerCall(AtomicReference<String> mdcAtClose) {
            this.mdcAtClose = mdcAtClose;
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            mdcAtClose.set(MDC.get(ObservabilityConstants.REQUEST_ID_MDC_KEY));
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("polaris.inventory.v1.InventoryService/CheckStock")
                    .setRequestMarshaller(new StringMarshaller())
                    .setResponseMarshaller(new StringMarshaller())
                    .build();
        }

        @Override
        public Attributes getAttributes() {
            return Attributes.EMPTY;
        }
    }

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
