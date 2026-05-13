package io.polaris.order.config;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

final class GrpcClientLoggingInterceptor implements ClientInterceptor {
    private static final Logger log = LoggerFactory.getLogger(GrpcClientLoggingInterceptor.class);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        String requestId = ObservabilityConstants.currentRequestId();
        long started = System.nanoTime();

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (ObservabilityConstants.hasText(requestId)) {
                    headers.put(ObservabilityConstants.REQUEST_ID_METADATA_KEY, requestId);
                }

                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        logCall(method.getFullMethodName(), status, started, requestId);
                        super.onClose(status, trailers);
                    }
                }, headers);
            }
        };
    }

    private void logCall(String method, Status status, long started, String requestId) {
        String previous = ObservabilityConstants.currentRequestId();
        try {
            if (ObservabilityConstants.hasText(requestId)) {
                MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, requestId);
            }
            log.info(
                    "grpc client call method={} status={} durationMs={} requestId={}",
                    method,
                    status.getCode(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    ObservabilityConstants.hasText(requestId) ? requestId : "none");
        } finally {
            if (ObservabilityConstants.hasText(previous)) {
                MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, previous);
            } else {
                MDC.remove(ObservabilityConstants.REQUEST_ID_MDC_KEY);
            }
        }
    }
}
