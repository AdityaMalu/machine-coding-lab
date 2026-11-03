package payments;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/** The SMTP relay (simulated). Slow, and sometimes down. Not part of the bug hunt. */
@Component
public class MailServer {

    /** One delivered mail, with the thread that sent it. */
    public record Mail(String to, String subject, String thread) {}

    private final List<Mail> delivered = new CopyOnWriteArrayList<>();
    private volatile boolean down;

    public void deliver(String to, String subject) {
        try {
            Thread.sleep(200); // SMTP handshake
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (down) throw new IllegalStateException("SMTP relay unavailable");
        delivered.add(new Mail(to, subject, Thread.currentThread().getName()));
    }

    public List<Mail> delivered() {
        return delivered;
    }

    public void setDown(boolean down) {
        this.down = down;
    }
}
