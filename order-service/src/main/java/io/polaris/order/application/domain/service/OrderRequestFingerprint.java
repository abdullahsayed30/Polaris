package io.polaris.order.application.domain.service;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import io.polaris.order.application.port.in.PlaceOrderLine;

final class OrderRequestFingerprint {
    private OrderRequestFingerprint() {
    }

    static String create(UUID customerId, List<PlaceOrderLine> lines) {
        MessageDigest digest = sha256();
        update(digest, customerId.toString());
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(lines.size()).array());
        for (PlaceOrderLine line : lines) {
            update(digest, line.sku());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(line.quantity()).array());
            update(digest, normalized(line.unitPrice()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String normalized(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
