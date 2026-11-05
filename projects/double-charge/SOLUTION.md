Five bugs behind the incident. The central one is idempotency: a retried request must return the first result, never charge again.

1. **Double charge on retries (race).** `find` then `save` on a `HashMap` lets two concurrent retries both see "no payment yet" and both charge. `IdempotencyStore.claim` uses `ConcurrentHashMap.putIfAbsent`, a single atomic step: exactly one caller wins and does the charge; the others wait on its `CompletableFuture` and get the same `Payment`.
2. **Same key, different request.** Each entry stores a request *fingerprint* (order, price, quantity, email). Reusing a key with a different body throws `IdempotencyConflictException` instead of silently returning someone else's payment.
3. **A declined card blocks retries.** On failure, `fail` removes the key and completes the future exceptionally, so waiters see the same error and the client may retry with the same key.
4. **Wrong totals.** `double` arithmetic turns 19.99 × 3 into 59.970000000000006. Money stays `BigDecimal` end to end, scaled to 2 places with `HALF_UP`.
5. **500 for a declined card.** The handler caught `IllegalStateException`; it now catches `PaymentDeclinedException` and returns **402 Payment Required**.
6. **Receipts block checkout.** `@Async` does nothing without `@EnableAsync`, so the slow mailer ran inline. Enabling it returns at once; the mailer also catches its own failures so a mail error can't fail a payment that already went through.
