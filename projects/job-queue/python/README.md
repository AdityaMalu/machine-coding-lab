# Retrying Job Queue (Python)

**Scenario.** Your team sends emails, generates invoices and resizes uploads in the background. Workers on many
machines pull jobs from a shared queue. Workers crash, hang and occasionally come back from the dead after their job
was given to someone else. Build the queue so that every job is handed out to **one worker at a time**, failures are
retried with backoff, and hopeless jobs end up in a **dead-letter** list instead of retrying forever.

## Given (package `jobs`, `models.py`)
`JobStatus` (`PENDING`, `RUNNING`, `SUCCEEDED`, `DEAD`, `CANCELLED`),
`RetryPolicy(max_attempts, base_backoff_millis, max_backoff_millis)` (validates itself),
`Lease(job_id, token, type, payload, attempt, lease_until)`,
`JobView(id, type, priority, status, attempts, next_run_at, last_error)` and `InvalidStateError`.
The `time` argument is any object with a `now_millis() -> int` method.

## Build `JobQueue(time, policy)` in `queue.py`

| Method | Behaviour |
|---|---|
| `submit(type, payload, priority, run_at_millis) -> str` | New `PENDING` job, ids `"J1"`, `"J2"`, ... Blank type → `ValueError`; `None` payload is stored as `""`. |
| `poll(worker_id, lease_millis) -> Lease \| None` | Hand the best **ready** job (`PENDING` and `run_at <= now`) to this worker: it becomes `RUNNING`, `attempts` goes up by 1, and it's leased until `now + lease_millis`. `None` if nothing is ready. Blank worker or `lease_millis <= 0` → `ValueError`. |
| `complete(job_id, token)` | → `SUCCEEDED`. |
| `fail(job_id, token, error=None)` | Records `last_error` (`None` → `"failed"`) and retries or dead-letters (below). |
| `view(job_id) -> JobView` | Current state. |
| `dead_letters() -> list[JobView]` | All `DEAD` jobs in submission order. |
| `retry_dead(job_id)` | Only from `DEAD`: back to `PENDING`, `attempts = 0`, ready now. |
| `cancel(job_id)` | Only from `PENDING` → `CANCELLED`. |

Unknown job id → `KeyError`. Wrong-state `retry_dead`/`cancel` → `InvalidStateError`.

## Rules
1. **Which job is best:** highest `priority` first; then earliest `run_at`; then submission order.
2. **Leases and tokens:** every successful `poll` creates a **new unique token**. `complete` and `fail` only work with
   the token of the job's *current* lease while it is `RUNNING`, otherwise `InvalidStateError`. That stops a
   zombie worker from completing a job that has since been given to someone else.
3. **Lease expiry:** a lease is valid while `now < lease_until`. Once it expires, the job counts as a failed attempt
   with error `"lease expired"`, as if `fail` had been called at the moment `lease_until` was reached. Expired leases
   are processed before any `poll`, `complete` or `fail` does its own work.
4. **Retry or dead-letter:** after a failed attempt, if `attempts < max_attempts` the job goes back to `PENDING` with
   `run_at = failure_time + backoff`, where after the *n*-th attempt
   `backoff = min(max_backoff, base_backoff × 2^(n-1))` (1s, 2s, 4s, ... with base 1000). Otherwise it becomes `DEAD`.
5. **Exactly one worker:** with many threads polling at once, a job is never leased to two workers at the same time.

## Talking points
At-least-once vs exactly-once delivery and why jobs must be idempotent; visibility timeouts in SQS; Celery's
`acks_late`; why add jitter to backoff; heartbeats to extend a lease; poison messages and dead-letter queues.
