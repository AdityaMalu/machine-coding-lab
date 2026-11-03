package payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentGateway gateway;
    private final IdempotencyStore idempotency;
    private final Ledger ledger;
    private final ReceiptMailer receipts;
    private final Clock clock;

    public PaymentService(PaymentGateway gateway, IdempotencyStore idempotency, Ledger ledger,
                          ReceiptMailer receipts, Clock clock) {
        this.gateway = gateway;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.receipts = receipts;
        this.clock = clock;
    }

    /** Charges for the request once per Idempotency-Key; a retry returns the original payment. */
    public Payment pay(String idempotencyKey, PayRequest request) {
        IdempotencyStore.Entry earlier = idempotency.claim(idempotencyKey, fingerprint(request));
        if (earlier != null) {
            try {
                return earlier.outcome().join();
            } catch (CompletionException e) {
                throw (RuntimeException) e.getCause();
            }
        }
        try {
            BigDecimal amount = amount(request);
            String ref = gateway.charge(request.orderId(), amount);
            Payment payment = new Payment(UUID.randomUUID().toString(), request.orderId(), amount, ref, clock.instant());
            ledger.record(payment);
            idempotency.finish(idempotencyKey, payment);
            receipts.sendReceipt(request.email(), payment); // @Async: returns at once
            return payment;
        } catch (RuntimeException e) {
            idempotency.fail(idempotencyKey, e);
            throw e;
        }
    }

    /** Money is BigDecimal end to end: 19.99 x 3 is exactly 59.97. */
    static BigDecimal amount(PayRequest r) {
        return r.unitPrice().multiply(BigDecimal.valueOf(r.quantity())).setScale(2, RoundingMode.HALF_UP);
    }

    /** What makes two requests "the same request" for idempotency. */
    static String fingerprint(PayRequest r) {
        return r.orderId() + "|" + r.unitPrice().stripTrailingZeros().toPlainString() + "|" + r.quantity() + "|" + r.email();
    }
}
