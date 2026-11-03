# Missing Money Incident (Spring Boot + JPA)

**INC-6018 · Sev 1 · Ledger service**

> **Reported by:** Finance, 07:55
> End-of-day totals don't add up: money leaves accounts and arrives nowhere. One customer's transfer to a
> closed account failed with 404, but their balance went down anyway. Compliance also found failed transfers
> with no audit entry at all. During yesterday's payroll run (many transfers out of the company account at
> once) the company account ended up *higher* than it should be. And someone managed to send a transfer of
> 0.00, which created a ledger row.

```
17:40:12 POST /transfers {from:1041, to:9999, amount:20.00} -> 404 Account not found
17:40:12 balance(1041): 50.00 -> 30.00
18:00:00 payroll: 12 transfers of 5.00 from 1 -> expected balance 40.00, actual 95.00
18:22:09 POST /transfers {from:2210, to:2211, amount:0.00} -> 201
```

You're on call. Package `ledger`: accounts, transfers and an audit trail on an H2 database through Spring
Data JPA. Read the code, run the tests, fix the service.

## How it must behave
- `POST /transfers {fromAccount, toAccount, amount}` moves `amount` from one account to the other. **All or
  nothing**: if any part fails, no balance changes and no transfer is recorded.
- Not enough money: **409** `Insufficient funds`. Unknown account: **404**. An amount that isn't positive, or
  the same account on both sides: **400**.
- **Every attempt is audited**: `COMPLETED` for a transfer, `FAILED` for one that failed, even though the
  failed transfer itself was rolled back.
- Transfers run concurrently (payroll, people paying each other at the same moment). No update may be lost,
  and transfers in opposite directions between the same two accounts must all complete.

## Rules of the round
- Don't change the tests. You may add repository methods.
- Expect layers: fixing one bug can make another test start failing. That's the incident unfolding, not a
  regression.
- The **✦ Assistant** tab can explain transactions, proxies, propagation and locking, but won't point at bugs.

## Follow-ups to think about
- Optimistic (`@Version` + retry) or pessimistic locking for a payroll run? What changes with 10x the load?
- Why is the order in which locks are taken important?
