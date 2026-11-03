package ledger;

import java.math.BigDecimal;
import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the audit trail. An entry must survive even when the transfer it describes is rolled back. */
@Service
public class AuditLog {

    private final AuditRepository entries;
    private final Clock clock;

    public AuditLog(AuditRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    /** Its own transaction: committed now, whatever happens to the caller's. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long from, Long to, BigDecimal amount, String outcome, String detail) {
        entries.save(new AuditEntry(from, to, amount, outcome, detail, clock.instant()));
    }
}
