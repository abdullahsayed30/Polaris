package io.polaris.inventory.adapter.out.observability;

import org.springframework.stereotype.Component;

import io.polaris.inventory.ObservabilityConstants;
import io.polaris.inventory.application.port.out.EventContext;

@Component
public class MdcEventContext implements EventContext {
    public String correlationId() {
        return ObservabilityConstants.currentRequestId();
    }
}
