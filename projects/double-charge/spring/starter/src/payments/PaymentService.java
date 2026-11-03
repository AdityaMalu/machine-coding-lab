package payments;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

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
        Payment earlier = idempotency.find(idempotencyKey);
        if (earlier != null) return earlier;

        BigDecimal amount = amount(request);
        String ref = gateway.charge(request.orderId(), amount);
        Payment payment = new Payment(UUID.randomUUID().toString(), request.orderId(), amount, ref, clock.instant());
        ledger.record(payment);
        idempotency.save(idempotencyKey, payment);
        receipts.sendReceipt(request.email(), payment); // @Async: returns at once
        return payment;
    }

    static BigDecimal amount(PayRequest r) {
        double total = r.unitPrice().doubleValue() * r.quantity();
        return BigDecimal.valueOf(total);
    }
}
