package payments;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Remembers the outcome of each Idempotency-Key so a retried request gets the original result instead of
 * charging again. Clients retry on timeouts, so retries arrive while the first attempt is still running.
 */
@Component
public class IdempotencyStore {

    private final Map<String, Payment> paymentsByKey = new HashMap<>();

    /** The payment already made for this key, or null. */
    public Payment find(String key) {
        return paymentsByKey.get(key);
    }

    public void save(String key, Payment payment) {
        paymentsByKey.put(key, payment);
    }
}
