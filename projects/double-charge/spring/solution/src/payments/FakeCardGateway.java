package payments;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Stands in for the card processor in this service's environment. It behaves like the real one: a call takes
 * a little while (network), amounts over 10,000.00 are declined, and every successful call is a real charge,
 * so it counts them. Not part of the bug hunt.
 */
@Component
public class FakeCardGateway implements PaymentGateway {

    static final BigDecimal LIMIT = new BigDecimal("10000.00");

    private final Map<String, AtomicInteger> chargesByOrder = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastAmountByOrder = new ConcurrentHashMap<>();
    private final AtomicLong refs = new AtomicLong();

    @Override
    public String charge(String orderId, BigDecimal amount) {
        try {
            Thread.sleep(15); // the processor is a network call away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (amount.compareTo(LIMIT) > 0) throw new PaymentDeclinedException(orderId, "over the card limit");
        chargesByOrder.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
        lastAmountByOrder.put(orderId, amount);
        return "ch_" + refs.incrementAndGet();
    }

    public int chargesFor(String orderId) {
        AtomicInteger n = chargesByOrder.get(orderId);
        return n == null ? 0 : n.get();
    }

    public BigDecimal lastAmountFor(String orderId) {
        return lastAmountByOrder.get(orderId);
    }
}
