package ledger;

import java.math.BigDecimal;
import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final AuditLog audit;
    private final Clock clock;

    public TransferService(AccountRepository accounts, TransferRepository transfers, AuditLog audit, Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.audit = audit;
        this.clock = clock;
    }

    /** Moves money between two accounts and audits the attempt. */
    public Transfer transfer(TransferRequest r) {
        BigDecimal amount = r.amount();
        if (amount.compareTo(BigDecimal.ZERO) < 0 || amount.equals(BigDecimal.ZERO)) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (r.fromAccount().equals(r.toAccount())) throw new IllegalArgumentException("can't transfer to the same account");
        try {
            return move(r.fromAccount(), r.toAccount(), amount);
        } catch (RuntimeException e) {
            audit.record(r.fromAccount(), r.toAccount(), amount, "FAILED", e.getMessage());
            throw e;
        }
    }

    /** All of it or none of it. */
    @Transactional
    private Transfer move(Long fromId, Long toId, BigDecimal amount) {
        Account from = accounts.findById(fromId).orElseThrow(() -> new AccountNotFoundException(fromId));
        if (from.getBalance().compareTo(amount) < 0) throw new InsufficientFundsException(fromId);
        from.debit(amount);
        accounts.save(from);

        Account to = accounts.findById(toId).orElseThrow(() -> new AccountNotFoundException(toId));
        to.credit(amount);
        accounts.save(to);

        Transfer t = transfers.save(new Transfer(fromId, toId, amount, clock.instant()));
        audit.record(fromId, toId, amount, "COMPLETED", "transfer " + t.getId());
        return t;
    }
}
