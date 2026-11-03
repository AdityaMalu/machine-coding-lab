package reminders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * The email provider (simulated). Each tenant's mail goes out under its own brand, so the sender's tenant
 * matters. Addresses starting with "flaky" fail on the first attempt, like a provider hiccup. Not part of
 * the bug hunt.
 */
@Component
public class Outbox {

    public record Delivery(String brandTenant, String email, String message, String thread) {}

    private final List<Delivery> delivered = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public void send(String brandTenant, String email, String message) {
        try {
            Thread.sleep(5); // provider API call
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int attempt = attempts.computeIfAbsent(email, k -> new AtomicInteger()).incrementAndGet();
        if (email.startsWith("flaky") && attempt == 1) throw new IllegalStateException("provider timeout for " + email);
        delivered.add(new Delivery(brandTenant, email, message, Thread.currentThread().getName()));
    }

    public List<Delivery> to(String email) {
        return delivered.stream().filter(d -> d.email().equals(email)).toList();
    }
}
