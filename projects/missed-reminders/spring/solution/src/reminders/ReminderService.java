package reminders;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderStore store;
    private final Notifier notifier;
    private final ThreadPoolTaskExecutor notifyExecutor;

    public ReminderService(ReminderStore store, Notifier notifier, ThreadPoolTaskExecutor notifyExecutor) {
        this.store = store;
        this.notifier = notifier;
        this.notifyExecutor = notifyExecutor;
    }

    public Reminder create(ReminderRequest req) {
        Reminder r = new Reminder(UUID.randomUUID().toString(), TenantContext.require(), req.email(), req.message(),
                req.dueAt(), Reminder.Status.PENDING);
        store.save(r);
        return r;
    }

    public List<Reminder> mine() {
        return store.forTenant(TenantContext.require());
    }

    /** "Send it now" from the web: claim it and hand it to the notify pool (the tenant travels with the task). */
    public boolean sendNow(String id) {
        Reminder r = store.find(id).filter(x -> x.tenant().equals(TenantContext.require()))
                .orElseThrow(() -> new IllegalArgumentException("no reminder " + id));
        if (!store.claim(r.id())) return false;
        notifyExecutor.execute(() -> deliver(r));
        return true;
    }

    /** Runs on a notify thread for a claimed reminder: SENT only once the provider accepted it. */
    void deliver(Reminder r) {
        try {
            notifier.deliver(r);
            store.markSent(r.id());
        } catch (RuntimeException e) {
            store.release(r.id());
            log.warn("Reminder {} not delivered, will retry: {}", r.id(), e.getMessage());
        }
    }
}
