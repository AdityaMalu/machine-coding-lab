# Retrying Job Queue (C++)

**Scenario.** Your team sends emails, generates invoices and resizes uploads in the background. Workers on many
machines pull jobs from a shared queue. Workers crash, hang and occasionally come back from the dead after their job
was given to someone else. Build the queue so that every job is handed out to **one worker at a time**, failures are
retried with backoff, and hopeless jobs end up in a **dead-letter** list instead of retrying forever.

## Given (`jobs_support.hpp`)
`Clock`, `enum class JobStatus { Pending, Running, Succeeded, Dead, Cancelled }`,
`RetryPolicy(maxAttempts, baseBackoffMillis, maxBackoffMillis)` (validates itself),
`Lease{jobId, token, type, payload, attempt, leaseUntil}`,
`JobView{id, type, priority, status, attempts, nextRunAt, lastError}` and the exception `InvalidState`.

## Build `JobQueue(const Clock&, RetryPolicy)` in `job_queue.hpp`

| Method | Behaviour |
|---|---|
| `std::string submit(type, payload, priority, runAtMillis)` | New `Pending` job, ids `"J1"`, `"J2"`, ... Blank type → `std::invalid_argument`. |
| `std::optional<Lease> poll(workerId, leaseMillis)` | Hand the best **ready** job (`Pending` and `runAt <= now`) to this worker: it becomes `Running`, `attempts` goes up by 1, and it's leased until `now + leaseMillis`. `std::nullopt` if nothing is ready. Blank worker or `leaseMillis <= 0` → `std::invalid_argument`. |
| `void complete(jobId, token)` | → `Succeeded`. |
| `void fail(jobId, token, error = "failed")` | Records `lastError` and retries or dead-letters (below). |
| `JobView view(jobId)` | Current state. |
| `std::vector<JobView> deadLetters()` | All `Dead` jobs in submission order. |
| `void retryDead(jobId)` | Only from `Dead`: back to `Pending`, `attempts = 0`, ready now. |
| `void cancel(jobId)` | Only from `Pending` → `Cancelled`. |

Unknown job id → `std::out_of_range`. Wrong-state `retryDead`/`cancel` → `InvalidState`.

## Rules
1. **Which job is best:** highest `priority` first; then earliest `runAt`; then submission order.
2. **Leases and tokens:** every successful `poll` creates a **new unique token**. `complete` and `fail` only work with
   the token of the job's *current* lease while it is `Running`, otherwise `InvalidState`. That stops a zombie
   worker from completing a job that has since been given to someone else.
3. **Lease expiry:** a lease is valid while `now < leaseUntil`. Once it expires, the job counts as a failed attempt
   with error `"lease expired"`, as if `fail` had been called at the moment `leaseUntil` was reached. Expired leases
   are processed before any `poll`, `complete` or `fail` does its own work.
4. **Retry or dead-letter:** after a failed attempt, if `attempts < maxAttempts` the job goes back to `Pending` with
   `runAt = failureTime + backoff`, where after the *n*-th attempt
   `backoff = min(maxBackoff, baseBackoff × 2^(n-1))` (1s, 2s, 4s, ... with base 1000), computed without
   overflowing `int64_t`. Otherwise it becomes `Dead`.
5. **Exactly one worker:** with many threads polling at once, a job is never leased to two workers at the same time.

## Talking points
At-least-once vs exactly-once delivery and why jobs must be idempotent; visibility timeouts in SQS; why add jitter
to backoff; heartbeats to extend a lease; poison messages and dead-letter queues.
