A build task: a job queue with priorities, leases, retries with exponential backoff, and a dead-letter list.

- **Picking the next job.** Among PENDING jobs whose `runAt <= now`: highest priority first, then the earliest `runAt`, then submission order (`seq`). The order is total, so ties are deterministic.
- **Leases, not locks.** `poll` marks the job RUNNING and hands out a unique token valid until `leaseUntil`. `complete` and `fail` must present the current token, so a slow worker whose lease expired can't complete a job that has since been given to someone else.
- **Expired leases are failed attempts.** Before any operation, RUNNING jobs past `leaseUntil` are treated as failing *at* `leaseUntil` (not "now"), so their backoff is computed from when the worker really disappeared.
- **Retry with backoff.** A failed attempt goes back to PENDING with `runAt = failedAt + min(max, base × 2^(attempt−1))`. The doubling loop stops at the cap, so there's no overflow. Once attempts reach `maxAttempts`, the job is DEAD and appears in `deadLetters()`.
- **Operator actions.** `retryDead` resets a DEAD job (attempts back to 0, runnable now); `cancel` only works on PENDING jobs. Illegal transitions throw instead of silently corrupting state.
- **Thread safety.** Every public method is `synchronized`: choosing a job and leasing it must be one step, or two workers could get the same job.
