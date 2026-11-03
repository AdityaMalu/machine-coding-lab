package reminders;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ReminderStore {

    private final Map<String, Reminder> byId = new ConcurrentHashMap<>();

    public void save(Reminder r) {
        byId.put(r.id(), r);
    }

    public Optional<Reminder> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Reminder> forTenant(String tenant) {
        return byId.values().stream().filter(r -> r.tenant().equals(tenant))
                .sorted(Comparator.comparing(Reminder::dueAt)).toList();
    }

    /** Pending reminders due at or before now, oldest first. */
    public List<Reminder> due(Instant now) {
        return byId.values().stream()
                .filter(r -> r.status() == Reminder.Status.PENDING && !r.dueAt().isAfter(now))
                .sorted(Comparator.comparing(Reminder::dueAt)).toList();
    }

    /** PENDING -> SENDING in one atomic step; false if someone else already claimed it. */
    public boolean claim(String id) {
        boolean[] won = {false};
        byId.computeIfPresent(id, (k, r) -> {
            if (r.status() != Reminder.Status.PENDING) return r;
            won[0] = true;
            return r.with(Reminder.Status.SENDING);
        });
        return won[0];
    }

    public void markSent(String id) {
        byId.computeIfPresent(id, (k, r) -> r.with(Reminder.Status.SENT));
    }

    /** Delivery failed: back to PENDING so the next run retries it. */
    public void release(String id) {
        byId.computeIfPresent(id, (k, r) -> r.status() == Reminder.Status.SENDING ? r.with(Reminder.Status.PENDING) : r);
    }
}
