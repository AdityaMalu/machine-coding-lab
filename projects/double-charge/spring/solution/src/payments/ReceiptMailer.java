package payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Emails the receipt. Runs in the background: a slow or broken mail server must never fail a payment. */
@Component
public class ReceiptMailer {

    private static final Logger log = LoggerFactory.getLogger(ReceiptMailer.class);

    private final MailServer mail;

    public ReceiptMailer(MailServer mail) {
        this.mail = mail;
    }

    @Async
    public void sendReceipt(String email, Payment payment) {
        try {
            mail.deliver(email, "Receipt for order " + payment.orderId() + ": " + payment.amount());
        } catch (RuntimeException e) {
            log.warn("Receipt for {} not sent: {}", payment.id(), e.getMessage());
        }
    }
}
