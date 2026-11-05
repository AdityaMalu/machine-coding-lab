A debug task: six bugs in the loan service. Most are about state transitions and locking.

1. **A zero principal was accepted.** The check is `principalCents <= 0`.
2. **Duplicate loan ids under load.** `counter++` isn't atomic; `AtomicLong.incrementAndGet()` is.
3. **Cancelled loans could be funded.** Only PENDING → ACTIVE is a legal funding step.
4. **Repayments lost under concurrency.** The new balance was computed *outside* the lock (read now, write later), so two concurrent repayments overwrote each other. The whole read-modify-write is now inside `synchronized (loan)`, together with the checks: the loan must be ACTIVE, and the amount positive and not above the balance. A balance of exactly 0 means PAID (the old code waited for it to go negative).
5. **Transfers deadlocked.** Locking `from` and then `to` means A→B and B→A can each hold one lock and wait forever. Both loans are now locked in a **global order** (by loan number).
6. **Failed transfers changed balances.** The old code debited the source before checking the target. Every check (both ACTIVE, same borrower, enough balance, not the same loan) now runs before any change, so a failed transfer changes nothing.
