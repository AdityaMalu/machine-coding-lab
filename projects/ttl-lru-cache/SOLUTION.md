A build task: an LRU cache with per-entry expiry, statistics and "single flight" loading.

- **LRU order for free.** `new LinkedHashMap<>(16, 0.75f, true)` keeps entries in *access* order: every `get` moves the key to the end, so the first key is always the least recently used. Making room is "remove the first key".
- **Expiry is checked lazily.** Each entry stores `expiresAt`; it's dead when `now >= expiresAt`. Reads remove dead entries as they find them (counting an expiration). Before evicting a live entry to make room, the cache purges expired ones first: an expired entry should never push out a live one. A TTL of 0 or less means "never expires", and overflow is treated as never.
- **Stats** count hits, misses, evictions (LRU removals) and expirations separately, which is what the tests check.
- **Single flight (`computeIfAbsent`).** When many threads miss the same key at once, only the first runs the loader; the rest wait on its `CompletableFuture` in `inflight` and share the result, or the same exception. The loader runs **outside** the cache lock, so a slow load doesn't block every other key. The in-flight entry is removed whether the load succeeds or fails, so a failed load can be retried.
- **Thread safety.** All map access is `synchronized`; the future is the only thing waited on outside the lock.
- **Validation.** Capacity ≥ 1, and null keys, values and loaders are rejected.
