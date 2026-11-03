package ledger;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** One attempted transfer, successful or not. Compliance requires an entry for every attempt. */
@Entity
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fromAccountId;
    private Long toAccountId;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    /** COMPLETED or FAILED */
    private String outcome;

    private String detail;
    private Instant at;

    protected AuditEntry() { // for JPA
    }

    public AuditEntry(Long fromAccountId, Long toAccountId, BigDecimal amount, String outcome, String detail, Instant at) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.outcome = outcome;
        this.detail = detail;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public Long getFromAccountId() {
        return fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAt() {
        return at;
    }
}
