package payments;

/** The processor refused the charge. Nothing was taken from the customer. */
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String orderId, String reason) {
        super("Payment for order " + orderId + " was declined: " + reason);
    }
}
