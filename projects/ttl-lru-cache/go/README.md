# TTL + LRU Cache (Go)

**Scenario.** A product-catalog service calls a slow pricing backend. You're adding an in-process cache in
front of it: bounded in size, entries that go stale after a while, and protection against a *cache stampede*
(a hot key expires and 50 goroutines all hit the backend at once).

## What to build (package `cache`, file `cache.go`)
`Clock`, `Stats` and `ErrInvalidArgument` are given (`models.go`). Implement a generic cache:

```go
func New[K comparable, V any](capacity int, defaultTTL int64, clock Clock) (*Cache[K, V], error)
```

| Method | Behaviour |
|---|---|
| `Get(key K) (V, bool)` | The value and `true`, or the zero value and `false` if absent or expired. A successful `Get` makes the entry **most recently used**. It does **not** extend its TTL. |
| `Put(key K, value V)` | Insert or replace with the default TTL. |
| `PutTTL(key K, value V, ttl int64)` | Per-entry TTL. `ttl <= 0` means the entry never expires. Replacing a key resets its TTL and makes it most recently used. |
| `Remove(key K) bool` | `true` if a live entry was removed. |
| `Len() int` | Number of **live** (non-expired) entries. |
| `GetOrLoad(key K, loader func(K) (V, error)) (V, error)` | Return the cached value, or load, cache and return it. See *single flight* below. |
| `Stats() Stats` | Counters since creation. |

## Rules
1. **Expiry:** an entry written at `t` with TTL `d` is live while `now < t + d` and expired at `now >= t + d`.
   Expired entries are removed lazily (when touched, or when room is needed) and each removal adds 1 to
   `Expirations`.
2. **Capacity:** inserting a **new** key into a full cache first removes **all expired entries**; only if the cache
   is still full is the **least recently used** live entry evicted (adds 1 to `Evictions`). Replacing an existing
   key never evicts anything.
3. **Stats:** `Get` and `GetOrLoad` count a **hit** when a live value is found, otherwise a **miss**.
4. **Single flight:** if many goroutines call `GetOrLoad` for the same missing key at the same time, the loader
   runs **once**; the others wait and receive the same value. A loader that returns an error: that error reaches
   every waiting caller, nothing is cached, and the next call tries again. A zero value with a `nil` error is a
   real value and **is** cached.
5. **Validation** → an error wrapping `ErrInvalidArgument` (check with `errors.Is`): `capacity < 1`, `nil` clock,
   `nil` loader. `defaultTTL <= 0` means "never expires by default".
6. **Goroutine-safe.** The cache must never hold more than `capacity` entries.

## Hints
- `container/list` gives a doubly linked list: keep entries in recency order and a `map[K]*list.Element` for
  O(1) lookup. `MoveToFront` on access, evict from `Back()`.
- For single flight, keep a `map[K]*call` of loads in progress, where `call` has a `done chan struct{}`, the value
  and the error. Run the loader **outside** the mutex, or one slow load blocks every other key.
  (This is what `golang.org/x/sync/singleflight` does.)

## Talking points
LRU vs LFU vs TTL-only; why stampedes happen; `singleflight`, `groupcache`, Ristretto; local cache vs Redis.
