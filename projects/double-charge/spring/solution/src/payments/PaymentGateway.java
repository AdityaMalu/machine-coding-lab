package payments;

import java.math.BigDecimal;

/** The card processor. Every call to charge() takes real money from the customer. */
public interface PaymentGateway {

    /** Charges the amount and returns the processor's reference; throws PaymentDeclinedException if refused. */
    String charge(String orderId, BigDecimal amount);
}
