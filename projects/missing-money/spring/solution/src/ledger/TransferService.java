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

    /**
     * Moves money between two accounts: all of it or none of it, one transaction. Both rows are locked, always
     * in id order, so two opposite transfers can't each hold one lock and wait for the other (deadlock).
     */
    @Transactional
    public Transfer transfer(TransferRequest r) {
        BigDecimal amount = r.amount();
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (r.fromAccount().equals(r.toAccount())) throw new IllegalArgumentException("can't transfer to the same account");
        try {
            Account first = lock(Math.min(r.fromAccount(), r.toAccount()));
            Account second = lock(Math.max(r.fromAccount(), r.toAccount()));
            Account from = first.getId().equals(r.fromAccount()) ? first : second;
            Account to = from == first ? second : first;
            if (from.getBalance().compareTo(amount) < 0) throw new InsufficientFundsException(from.getId());
            from.debit(amount);
            to.credit(amount);
            Transfer t = transfers.save(new Transfer(from.getId(), to.getId(), amount, clock.instant()));
            audit.record(from.getId(), to.getId(), amount, "COMPLETED", "transfer " + t.getId());
            return t;
        } catch (RuntimeException e) {
            audit.record(r.fromAccount(), r.toAccount(), amount, "FAILED", e.getMessage());
            throw e;
        }
    }

    private Account lock(Long id) {
        return accounts.findForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
