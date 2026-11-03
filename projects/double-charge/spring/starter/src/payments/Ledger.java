package payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/** Every successful payment, for finance's daily reconciliation against the processor. */
@Component
public class Ledger {

    private final List<Payment> payments = new CopyOnWriteArrayList<>();

    public void record(Payment payment) {
        payments.add(payment);
    }

    public List<Payment> forOrder(String orderId) {
        return payments.stream().filter(p -> p.orderId().equals(orderId)).toList();
    }

    public BigDecimal totalFor(String orderId) {
        return forOrder(orderId).stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
