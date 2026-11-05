Four bugs, all about transaction boundaries and locking in Spring + JPA.

1. **Failed transfers lose money.** `@Transactional` on a *private* method does nothing: Spring's proxy only intercepts public calls from outside the bean. The debit was committed, then the credit failed. `@Transactional` now sits on the public `transfer`, so debit, credit and the `Transfer` row commit together or roll back together.
2. **Audit entries vanish.** The audit ran inside the caller's transaction, so when a transfer failed and rolled back, its "FAILED" audit row was rolled back with it. `Propagation.REQUIRES_NEW` gives `AuditLog.record` its own transaction that commits regardless.
3. **Concurrent transfers corrupt balances.** Two transfers read the same balance and both write (lost update). `findForUpdate` uses `@Lock(PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`). Both rows are locked **in id order**, so A→B and B→A can't each hold one lock and wait for the other (no deadlock).
4. **Zero slips through.** `amount.equals(BigDecimal.ZERO)` is false for `0.00` (different scale). `signum() <= 0` rejects every zero and negative amount.

Entities loaded in the transaction are managed, so `debit`/`credit` are saved at commit without explicit `save` calls.
