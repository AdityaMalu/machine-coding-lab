package reminders;

import org.springframework.stereotype.Component;

/** Sends one reminder under the current tenant's brand. */
@Component
public class Notifier {

    private final Outbox outbox;

    public Notifier(Outbox outbox) {
        this.outbox = outbox;
    }

    public void deliver(Reminder r) {
        String brand = TenantContext.get() == null ? "default" : TenantContext.get();
        outbox.send(brand, r.email(), r.message());
    }
}
