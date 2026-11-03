# Missed Reminders Incident (Spring Boot)

**INC-7322 · Sev 2 · Reminders service (multi-tenant)**

> **Reported by:** Customer Success, 09:20 Monday
> Acme's staff got reminders branded as Globex (and some as "default"). Several customers say reminders
> arrive a full poll late, or not at all. On Monday at 9:00, when most teams' standup reminders fire, about
> two thirds were never sent. After the mail provider's outage last week, those reminders were never retried.
> Since we scaled to two instances, some people get every reminder twice. And an integration that forgot the
> `X-Tenant` header created reminders under some *other* tenant instead of being rejected.

```
09:00:00.004 DispatchJob: 40 due
09:00:00.031 WARN TaskRejectedException: ExecutorService in active state did not accept task
09:00:00.512 outbox: 11 delivered
08:59:58.770 outbox: brand=globex to=jo@acme.example "Standup"
```

You're on call. Package `reminders`. `Outbox` simulates the email provider (addresses starting with `flaky`
fail on the first try) and isn't part of the bug hunt. `DispatchJob` normally runs on a schedule; the tests
call `runOnce()` directly and control time through the app's `Clock` bean.

## How it must behave
- `POST /reminders` (header `X-Tenant`) creates a reminder for that tenant; a request **without** the header
  is **400** `Tenant required`, always. `GET /reminders` lists only the caller's tenant's reminders.
- Each run sends every reminder that is due **at or before now** (by the app's `Clock`), each under its own
  tenant's brand. Reminders due later wait for a later run.
- A reminder is marked sent only once the provider accepted it; a failed delivery is retried on the next run.
- A burst of due reminders all go out on that run, even though the notify pool is small (the provider
  rate-limits us; keep the pool at 2 threads).
- Two instances may run the job at the same moment; a reminder is still delivered **exactly once**.
- `POST /reminders/{id}/send` delivers in the background (on the notify pool) under the caller's brand.

## Rules of the round
- Don't change the tests or `Outbox`; keep the notify pool at 2 threads.
- The **✦ Assistant** tab can explain ThreadLocals, thread pools, `TaskDecorator`, scheduling and the code,
  but won't point at bugs.

## Follow-ups to think about
- In production the store is a database. How would you claim a reminder atomically across instances?
- What would you monitor to catch the Monday burst before customers do?
