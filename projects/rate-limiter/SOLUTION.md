A build task: two classic limiters, per client, correct under 100 concurrent threads.

**Token bucket.** Each client has a bucket that starts full. On each request: refill by `elapsed × rate` (capped at `capacity`), then take one token if there is one. Tokens are fractional, so small refills add up across calls (0.4 + 0.6 = one token) instead of being rounded away; a tiny epsilon absorbs floating-point drift. After a long idle period the bucket holds at most `capacity`, never more.

**Sliding window log.** Each client keeps a queue of accepted timestamps. On each request, drop timestamps with `t <= now - window` (so a request slides out *exactly* at the boundary), then accept only if fewer than `maxRequests` remain. Rejected requests aren't recorded, so they don't count against the limit.

**Concurrency.** Each client's state is created exactly once (`computeIfAbsent` on a concurrent map); then a lock on *that client's* bucket or queue makes refill-and-take (or prune-and-add) atomic. Different clients never block each other, and 100 threads can't exceed the limit.

**Time** comes from the injected `TimeSource`, so tests control the clock. Invalid arguments are rejected in the constructor.
