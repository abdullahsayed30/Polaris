package io.polaris.order.application.port.in;

import java.math.BigDecimal;

public record PlaceOrderLine(String sku, int quantity, BigDecimal unitPrice) {
}
