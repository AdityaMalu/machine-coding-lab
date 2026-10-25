package shop;

import java.math.BigDecimal;

public interface PaymentGateway {
    /** Charges the amount and returns a payment reference. */
    String charge(String orderId, BigDecimal amount);
}
