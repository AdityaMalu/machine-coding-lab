package payments;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Remembers the outcome of each Idempotency-Key so a retried request gets the original result instead of
 * charging again. Clients retry on timeouts, so retries arrive while the first attempt is still running.
 */
@Component
public class IdempotencyStore {

    /** One key's request fingerprint and its (possibly still running) outcome. */
    public record Entry(String fingerprint, CompletableFuture<Payment> outcome) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Claims the key. Returns null if this caller is the first and must do the work (then call finish or fail),
     * or the existing entry to wait on. Reusing a key for a different request is a conflict.
     */
    public Entry claim(String key, String fingerprint) {
        Entry mine = new Entry(fingerprint, new CompletableFuture<>());
        Entry existing = entries.putIfAbsent(key, mine); // one atomic step: only one caller can win
        if (existing == null) return null;
        if (!existing.fingerprint().equals(fingerprint)) throw new IdempotencyConflictException(key);
        return existing;
    }

    public void finish(String key, Payment payment) {
        entries.get(key).outcome().complete(payment);
    }

    /** A failed attempt (e.g. declined) frees the key so the client can retry with the same key. */
    public void fail(String key, RuntimeException e) {
        Entry entry = entries.remove(key);
        if (entry != null) entry.outcome().completeExceptionally(e);
    }
}
