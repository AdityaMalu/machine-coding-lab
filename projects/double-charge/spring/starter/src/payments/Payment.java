package payments;

import java.math.BigDecimal;
import java.time.Instant;

public record Payment(String id, String orderId, BigDecimal amount, String gatewayRef, Instant paidAt) {}
