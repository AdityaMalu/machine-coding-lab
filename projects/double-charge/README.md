# Duplicate Charges Incident (Spring Boot)

**INC-4471 · Sev 2 · Payments API**

> **Reported by:** Support, 08:42
> Three customers say they were charged twice for one order. Finance's reconciliation also shows charges
> like `59.97000000000001` that the bank rejects as malformed. Separately, mobile says a declined card makes
> the app show "Something went wrong" instead of "Card declined". And since yesterday's mail-server
> maintenance, some customers got "Payment declined" although their card *was* charged.

```
08:12:04.117 POST /payments  Idempotency-Key=7f3e…  order=ord-88213  -> 201  (charge ch_9921)
08:12:04.119 POST /payments  Idempotency-Key=7f3e…  order=ord-88213  -> 201  (charge ch_9922)   <- mobile retry
08:12:04.121 POST /payments  Idempotency-Key=7f3e…  order=ord-88213  -> 201  (charge ch_9923)   <- mobile retry
09:30:51.402 POST /payments  order=ord-90117 amount=12000.00 -> 500 Internal Server Error
10:05:33.880 POST /payments  order=ord-90388 -> 402 "Payment declined"   (ledger: ch_10441 charged 18.00)
```

You're on call. Package `payments`: a small Spring Boot service. Read the code (start with
`PaymentController` and `PaymentService`), run the tests, and fix the service so every test passes.
`FakeCardGateway` and `MailServer` simulate external systems and behave like the real ones; the bugs aren't
there.

## How it must behave

`POST /payments` with header `Idempotency-Key` and body `{orderId, unitPrice, quantity, email}`:

- **201** with the payment. The amount is `unitPrice × quantity`, exact to the cent.
- **Idempotency:** clients retry on timeouts, often while the first request is still in flight. A retry with
  the same key and the same request returns the original payment and **never charges again**, however many
  retries arrive at once. The same key with a *different* request is **422** `Idempotency key reused`.
  A declined attempt doesn't use up the key.
- **402** `Payment declined` when the processor declines. Nothing is recorded in the ledger.
- **400** for a missing `Idempotency-Key` or an invalid body.
- **Receipts** are emailed in the background. A slow or broken mail server must never delay or fail a payment.

## Rules of the round
- Don't change the tests, `FakeCardGateway` or `MailServer`.
- There's more than one bug, and some only show up after you fix another. Run the tests often.
- The **✦ Assistant** tab can explain the code, the tests and the errors (with your own Anthropic API key),
  but won't point at bugs. Try **Interview mode** for a timed round with a report at the end.

## Follow-ups to think about
- Where would the idempotency record live with three instances behind a load balancer?
- What should the response be for a retry that arrives while the first attempt is still charging?
