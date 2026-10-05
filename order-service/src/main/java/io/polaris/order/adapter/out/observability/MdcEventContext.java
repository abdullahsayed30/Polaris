package io.polaris.order.adapter.out.observability;

import org.springframework.stereotype.Component;

import io.polaris.order.ObservabilityConstants;
import io.polaris.order.application.port.out.EventContext;

@Component
public class MdcEventContext implements EventContext {
    public String correlationId() {
        return ObservabilityConstants.currentRequestId();
    }
}
