A build task: a concurrent pipeline with three guarantees (order, failure isolation, a memory budget). Each guarantee maps to one tool.

- **The memory budget is a `Semaphore`** with one permit per byte of the budget. Before an image is admitted, the submitting thread acquires `sizeBytes()` permits; the worker releases them in `finally` when it's done, success or failure. So the bytes in flight can never exceed the budget, and admission simply waits when it's full. The semaphore is **fair** (FIFO), so a big image isn't starved by a stream of small ones.
- **Too-big images** (larger than the whole budget) could never get their permits and would wait forever. They're failed up front with "exceeds memory budget".
- **Order.** One `Future` per input, collected in input order; reading them in that order puts `results.get(i)` with `inputs.get(i)` no matter which finished first.
- **Failure isolation.** Each task catches its own exception and returns `Result.failure(name, message)`, so one bad image never cancels the others.
- **Transforms don't mutate their input.** Each one builds a new pixel array: `255 - v` to invert, a clamp to 0..255 to brighten, and `x → w − 1 − x` within each row to flip. Images can then be shared between threads safely.
- **Cleanup.** The pool is shut down in `finally`, and if a submit is rejected the permits are given back.
