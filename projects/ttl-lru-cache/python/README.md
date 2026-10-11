# TTL + LRU Cache (Python)

**Scenario.** A product-catalog service calls a slow pricing backend. You're adding an in-process cache in
front of it: bounded in size, entries that go stale after a while, and protection against a *cache stampede*
(a hot key expires and 50 request threads all hit the backend at once).

## What to build (package `cache`, file `ttl_lru_cache.py`)
`CacheStats(hits, misses, evictions, expirations)` is given. The `time` argument is any object with a
`now_millis() -> int` method (tests pass a fake clock). Implement `TtlLruCache(capacity, default_ttl_millis, time)`:

| Method | Behaviour |
|---|---|
| `get(key)` | The value, or `None` if absent or expired. A successful `get` makes the entry **most recently used**. It does **not** extend its TTL. |
| `put(key, value, ttl_millis=None)` | Insert or replace. `None` means the default TTL; `ttl_millis <= 0` means the entry never expires. Replacing a key resets its TTL and makes it most recently used. |
| `remove(key)` | `True` if a live entry was removed. |
| `size()` | Number of **live** (non-expired) entries. |
| `compute_if_absent(key, loader)` | Return the cached value, or call `loader(key)`, cache and return the result. See *single flight* below. |
| `stats()` | A `CacheStats` with the counters since creation. |

## Rules
1. **Expiry:** an entry written at `t` with TTL `d` is live while `now < t + d` and expired at `now >= t + d`.
   Expired entries are removed lazily (when touched, or when room is needed) and each removal adds 1 to
   `expirations`.
2. **Capacity:** inserting a **new** key into a full cache first removes **all expired entries**; only if the cache
   is still full is the **least recently used** live entry evicted (adds 1 to `evictions`). Replacing an existing
   key never evicts anything.
3. **Stats:** `get` and `compute_if_absent` count a **hit** when a live value is found, otherwise a **miss**.
4. **Single flight:** if many threads call `compute_if_absent` for the same missing key at the same time, the loader
   runs **once**; the other callers wait and receive the same value. A loader that raises: the exception reaches
   every waiting caller, nothing is cached, and the next call tries again. A `None` result is returned but not cached.
5. **Validation** → `ValueError`: `capacity < 1`, `time` is `None`, `None` key, `None` value, `None` loader.
   `default_ttl_millis <= 0` means "never expires by default".
6. **Thread-safe.** The cache must never hold more than `capacity` entries.

## Hints
- `collections.OrderedDict` keeps insertion order and has `move_to_end(key)` and `popitem(last=False)`: the first
  key is then the least recently used one.
- For single flight, keep a dict of loads in progress (a small object with a `threading.Event`, the value and the
  error). Run the loader **outside** the cache lock, or one slow load blocks every other key.

## Talking points
LRU vs LFU vs TTL-only; why stampedes happen; how `functools.lru_cache`, Caffeine and Guava differ
(`refreshAfterWrite`, probabilistic early expiry); local cache vs Redis.
