package reminders;

import java.time.Instant;

public record Reminder(String id, String tenant, String email, String message, Instant dueAt, Status status) {

    public enum Status { PENDING, SENDING, SENT }

    public Reminder with(Status s) {
        return new Reminder(id, tenant, email, message, dueAt, s);
    }
}
