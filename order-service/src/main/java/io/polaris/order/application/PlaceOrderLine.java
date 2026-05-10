package io.polaris.order.application;

import java.math.BigDecimal;

public record PlaceOrderLine(String sku, int quantity, BigDecimal unitPrice) {
}
