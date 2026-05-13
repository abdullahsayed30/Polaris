package io.polaris.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

class GrpcClientLoggingInterceptorTest {
    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void propagatesRequestIdMetadataAndRestoresMdc() {
        MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, "request-123");
        AtomicReference<Metadata> metadata = new AtomicReference<>();
        GrpcClientLoggingInterceptor interceptor = new GrpcClientLoggingInterceptor();

        ClientCall<String, String> call = interceptor.interceptCall(method(), CallOptions.DEFAULT, new CapturingChannel(metadata));

        call.start(new ClientCall.Listener<>() {
        }, new Metadata());

        assertThat(metadata.get().get(ObservabilityConstants.REQUEST_ID_METADATA_KEY)).isEqualTo("request-123");
        assertThat(MDC.get(ObservabilityConstants.REQUEST_ID_MDC_KEY)).isEqualTo("request-123");
    }

    private MethodDescriptor<String, String> method() {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("polaris.inventory.v1.InventoryService/CheckStock")
                .setRequestMarshaller(new StringMarshaller())
                .setResponseMarshaller(new StringMarshaller())
                .build();
    }

    private static final class CapturingChannel extends Channel {
        private final AtomicReference<Metadata> metadata;

        private CapturingChannel(AtomicReference<Metadata> metadata) {
            this.metadata = metadata;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> methodDescriptor,
                CallOptions callOptions) {
            return new ClientCall<>() {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    metadata.set(headers);
                    responseListener.onClose(Status.OK, new Metadata());
                }

                @Override
                public void request(int numMessages) {
                }

                @Override
                public void cancel(String message, Throwable cause) {
                }

                @Override
                public void halfClose() {
                }

                @Override
                public void sendMessage(ReqT message) {
                }
            };
        }

        @Override
        public String authority() {
            return "inventory-service";
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
