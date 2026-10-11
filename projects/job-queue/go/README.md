# Retrying Job Queue (Go)

**Scenario.** Your team sends emails, generates invoices and resizes uploads in the background. Workers on many
machines pull jobs from a shared queue. Workers crash, hang and occasionally come back from the dead after their job
was given to someone else. Build the queue so that every job is handed out to **one worker at a time**, failures are
retried with backoff, and hopeless jobs end up in a **dead-letter** list instead of retrying forever.

## Given (package `jobs`, `models.go`)
`Clock`, `Status` (`StatusPending`, `StatusRunning`, `StatusSucceeded`, `StatusDead`, `StatusCancelled`),
`NewRetryPolicy(maxAttempts, baseBackoffMillis, maxBackoffMillis)` (validates), `Lease`, `JobView`, and the
errors `ErrInvalidArgument`, `ErrNotFound` and `ErrInvalidState`.

## Build `NewQueue(clock, policy) (*Queue, error)` in `queue.go`

| Method | Behaviour |
|---|---|
| `Submit(jobType, payload string, priority int, runAtMillis int64) (string, error)` | New `PENDING` job, ids `"J1"`, `"J2"`, ... Blank type → `ErrInvalidArgument`. |
| `Poll(worker string, leaseMillis int64) (Lease, bool, error)` | Hand the best **ready** job (`PENDING` and `runAt <= now`) to this worker: it becomes `RUNNING`, `Attempts` goes up by 1, and it's leased until `now + leaseMillis`. `ok == false` if nothing is ready. Blank worker or `leaseMillis <= 0` → `ErrInvalidArgument`. |
| `Complete(jobID, token string) error` | → `SUCCEEDED`. |
| `Fail(jobID, token, errMsg string) error` | Records `LastError` (`""` → `"failed"`) and retries or dead-letters (below). |
| `View(jobID string) (JobView, error)` | Current state. |
| `DeadLetters() []JobView` | All `DEAD` jobs in submission order. |
| `RetryDead(jobID string) error` | Only from `DEAD`: back to `PENDING`, `Attempts = 0`, ready now. |
| `Cancel(jobID string) error` | Only from `PENDING` → `CANCELLED`. |

Unknown job id → `ErrNotFound`. Wrong-state `RetryDead`/`Cancel` → `ErrInvalidState`. Return them wrapped
(`fmt.Errorf("...: %w", ErrNotFound)`); tests check with `errors.Is`.

## Rules
1. **Which job is best:** highest `priority` first; then earliest `runAt`; then submission order.
2. **Leases and tokens:** every successful `Poll` creates a **new unique token**. `Complete` and `Fail` only work with
   the token of the job's *current* lease while it is `RUNNING`, otherwise `ErrInvalidState`. That stops a zombie
   worker from completing a job that has since been given to someone else.
3. **Lease expiry:** a lease is valid while `now < LeaseUntil`. Once it expires, the job counts as a failed attempt
   with error `"lease expired"`, as if `Fail` had been called at the moment `LeaseUntil` was reached. Expired leases
   are processed before any `Poll`, `Complete` or `Fail` does its own work.
4. **Retry or dead-letter:** after a failed attempt, if `Attempts < MaxAttempts` the job goes back to `PENDING` with
   `runAt = failureTime + backoff`, where after the *n*-th attempt
   `backoff = min(maxBackoff, baseBackoff × 2^(n-1))` (1s, 2s, 4s, ... with base 1000). Otherwise it becomes `DEAD`.
5. **Exactly one worker:** with many goroutines polling at once, a job is never leased to two workers at the same time.

## Talking points
At-least-once vs exactly-once delivery and why jobs must be idempotent; visibility timeouts in SQS; why add jitter
to backoff; heartbeats to extend a lease; poison messages and dead-letter queues.
