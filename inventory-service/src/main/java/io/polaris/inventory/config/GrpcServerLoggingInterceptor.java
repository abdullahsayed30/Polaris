package io.polaris.inventory.config;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

final class GrpcServerLoggingInterceptor implements ServerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLoggingInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String requestId = requestId(headers);
        String method = call.getMethodDescriptor().getFullMethodName();
        long started = System.nanoTime();

        ServerCall<ReqT, RespT> loggingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                withRequestId(requestId, () -> {
                    log.info(
                            "grpc server call method={} status={} durationMs={} requestId={}",
                            method,
                            status.getCode(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            requestId);
                    super.close(status, trailers);
                });
            }
        };

        ServerCall.Listener<ReqT> listener = withRequestId(requestId, () -> next.startCall(loggingCall, headers));
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onMessage(ReqT message) {
                withRequestId(requestId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withRequestId(requestId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                withRequestId(requestId, super::onCancel);
            }

            @Override
            public void onComplete() {
                withRequestId(requestId, super::onComplete);
            }

            @Override
            public void onReady() {
                withRequestId(requestId, super::onReady);
            }
        };
    }

    private String requestId(Metadata headers) {
        String provided = headers.get(ObservabilityConstants.REQUEST_ID_METADATA_KEY);
        if (ObservabilityConstants.hasText(provided)) {
            return provided;
        }
        return UUID.randomUUID().toString();
    }

    private <T> T withRequestId(String requestId, Supplier<T> action) {
        String previous = ObservabilityConstants.currentRequestId();
        try {
            MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, requestId);
            return action.get();
        } finally {
            restore(previous);
        }
    }

    private void withRequestId(String requestId, Runnable action) {
        String previous = ObservabilityConstants.currentRequestId();
        try {
            MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, requestId);
            action.run();
        } finally {
            restore(previous);
        }
    }

    private void restore(String previous) {
        if (ObservabilityConstants.hasText(previous)) {
            MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, previous);
        } else {
            MDC.remove(ObservabilityConstants.REQUEST_ID_MDC_KEY);
        }
    }
}
