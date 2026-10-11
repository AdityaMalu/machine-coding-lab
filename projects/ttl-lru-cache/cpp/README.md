# TTL + LRU Cache (C++)

**Scenario.** A product-catalog service calls a slow pricing backend. You're adding an in-process cache in
front of it: bounded in size, entries that go stale after a while, and protection against a *cache stampede*
(a hot key expires and 50 request threads all hit the backend at once).

## What to build (header `ttl_lru_cache.hpp`, C++20)
`Clock` and `CacheStats` are given (`cache_support.hpp`). Implement:

```cpp
template <typename K, typename V>
class TtlLruCache {
public:
    using Loader = std::function<std::optional<V>(const K&)>;
    TtlLruCache(int capacity, int64_t defaultTtlMillis, const Clock& clock);
    ...
};
```

| Method | Behaviour |
|---|---|
| `std::optional<V> get(const K&)` | The value, or `std::nullopt` if absent or expired. A successful `get` makes the entry **most recently used**. It does **not** extend its TTL. |
| `void put(const K&, V)` | Insert or replace with the default TTL. |
| `void put(const K&, V, int64_t ttlMillis)` | Per-entry TTL. `ttlMillis <= 0` means the entry never expires. Replacing a key resets its TTL and makes it most recently used. |
| `bool remove(const K&)` | `true` if a live entry was removed. |
| `int size()` | Number of **live** (non-expired) entries. |
| `std::optional<V> computeIfAbsent(const K&, const Loader&)` | Return the cached value, or load, cache and return it. See *single flight* below. |
| `CacheStats stats()` | Counters since creation. |

## Rules
1. **Expiry:** an entry written at `t` with TTL `d` is live while `now < t + d` and expired at `now >= t + d`.
   Expired entries are removed lazily (when touched, or when room is needed) and each removal adds 1 to
   `expirations`.
2. **Capacity:** inserting a **new** key into a full cache first removes **all expired entries**; only if the cache
   is still full is the **least recently used** live entry evicted (adds 1 to `evictions`). Replacing an existing
   key never evicts anything.
3. **Stats:** `get` and `computeIfAbsent` count a **hit** when a live value is found, otherwise a **miss**.
4. **Single flight:** if many threads call `computeIfAbsent` for the same missing key at the same time, the loader
   runs **once**; the other callers wait and receive the same value. A loader that throws: the exception reaches
   every waiting caller, nothing is cached, and the next call tries again. A `std::nullopt` result is returned but
   not cached.
5. **Validation** → `std::invalid_argument`: `capacity < 1`, an empty loader. `defaultTtlMillis <= 0` means
   "never expires by default".
6. **Thread-safe.** The cache must never hold more than `capacity` entries.

## Hints
- `std::list` keeps entries in recency order and `std::unordered_map<K, std::list<...>::iterator>` finds them in
  O(1); `list.splice(list.begin(), list, it)` moves an entry to the front without copying it.
- For single flight, keep a `std::unordered_map<K, std::shared_future<std::optional<V>>>` of loads in progress.
  The loader fulfils a `std::promise` (`set_value`, or `set_exception(std::current_exception())`), and
  `shared_future::get()` rethrows the exception in every waiter. Run the loader **outside** the mutex, or one
  slow load blocks every other key.

## Talking points
LRU vs LFU vs TTL-only; why stampedes happen; how Caffeine/Guava handle this (`refreshAfterWrite`, probabilistic
early expiry); local cache vs Redis.
