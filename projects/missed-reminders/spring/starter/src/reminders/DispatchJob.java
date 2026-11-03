package reminders;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** Every poll interval: send each reminder that is due. Several app instances may run this at the same time. */
@Component
public class DispatchJob {

    private final ReminderStore store;
    private final Notifier notifier;
    private final ThreadPoolTaskExecutor notifyExecutor;
    private final Clock clock;

    public DispatchJob(ReminderStore store, Notifier notifier, ThreadPoolTaskExecutor notifyExecutor, Clock clock) {
        this.store = store;
        this.notifier = notifier;
        this.notifyExecutor = notifyExecutor;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${reminders.poll-ms:3600000}", fixedDelayString = "${reminders.poll-ms:3600000}")
    public void runOnce() {
        Instant now = Instant.now();
        for (Reminder r : store.due(now)) {
            store.markSent(r.id());
            TenantContext.set(r.tenant());
            try {
                notifyExecutor.execute(() -> notifier.deliver(r));
            } finally {
                TenantContext.clear();
            }
        }
    }
}
