import random
import threading
import time as real_time

from cache import TtlLruCache
from labtest import (assert_equal, assert_false, assert_is_none, assert_raises, assert_true, run_concurrently,
                     test)


class ManualTime:
    def __init__(self):
        self.now = 1_000
        self._lock = threading.Lock()

    def now_millis(self):
        with self._lock:
            return self.now

    def advance(self, ms):
        with self._lock:
            self.now += ms


class TtlLruCacheTest:

    @test("put then get; missing keys return None")
    def basics(self):
        c = TtlLruCache(3, 0, ManualTime())
        c.put("a", "1")
        assert_equal("1", c.get("a"))
        assert_is_none(c.get("nope"), "missing key")
        c.put("a", "2")
        assert_equal("2", c.get("a"), "put replaces")
        assert_equal(1, c.size())

    @test("evicts the least recently used entry; get refreshes recency")
    def lru(self):
        c = TtlLruCache(3, 0, ManualTime())
        c.put("a", "1")
        c.put("b", "2")
        c.put("c", "3")
        c.get("a")             # order is now b, c, a
        c.put("d", "4")        # evicts b
        assert_is_none(c.get("b"), "b was least recently used")
        assert_equal("1", c.get("a"))
        assert_equal("3", c.get("c"))
        assert_equal("4", c.get("d"))
        assert_equal(1, c.stats().evictions)

    @test("replacing a key refreshes recency and never evicts")
    def replace_refreshes(self):
        c = TtlLruCache(2, 0, ManualTime())
        c.put("a", "1")
        c.put("b", "2")
        c.put("a", "1b")       # a becomes most recent, no eviction
        assert_equal(0, c.stats().evictions, "replace must not evict")
        c.put("c", "3")        # evicts b, not a
        assert_is_none(c.get("b"))
        assert_equal("1b", c.get("a"))

    @test("expiry boundary: live until t+ttl, expired exactly at t+ttl")
    def expiry_boundary(self):
        t = ManualTime()
        c = TtlLruCache(10, 500, t)
        c.put("k", "v")
        t.advance(499)
        assert_equal("v", c.get("k"), "1ms before expiry")
        t.advance(1)
        assert_is_none(c.get("k"), "exactly at expiry")
        assert_equal(1, c.stats().expirations)
        assert_equal(0, c.size())

    @test("get does not extend the TTL")
    def get_does_not_extend(self):
        t = ManualTime()
        c = TtlLruCache(10, 1_000, t)
        c.put("k", "v")
        for _ in range(9):
            t.advance(100)
            assert_equal("v", c.get("k"))
        t.advance(100)
        assert_is_none(c.get("k"), "expires 1000ms after the write, not after the last read")

    @test("per-entry TTL; ttl <= 0 never expires")
    def per_entry_ttl(self):
        t = ManualTime()
        c = TtlLruCache(10, 1_000, t)
        c.put("short", "s", 100)
        c.put("forever", "f", 0)
        c.put("default", "d")
        t.advance(100)
        assert_is_none(c.get("short"))
        assert_equal("d", c.get("default"))
        t.advance(1_000_000_000)
        assert_equal("f", c.get("forever"))
        assert_is_none(c.get("default"))

    @test("replacing a key resets its TTL")
    def replace_resets_ttl(self):
        t = ManualTime()
        c = TtlLruCache(10, 1_000, t)
        c.put("k", "v1")
        t.advance(900)
        c.put("k", "v2")
        t.advance(900)
        assert_equal("v2", c.get("k"), "new TTL started at the replace")

    @test("a full cache drops expired entries before evicting live ones")
    def expired_before_lru(self):
        t = ManualTime()
        c = TtlLruCache(3, 10_000, t)
        c.put("a", "1", 100)
        c.put("b", "2")
        c.put("c", "3")
        t.advance(50)
        assert_equal("1", c.get("a"))    # a is now most recently used...
        t.advance(150)                   # ...but expired
        c.put("d", "4")
        assert_equal("2", c.get("b"), "b must survive: an expired entry was available")
        assert_equal("3", c.get("c"))
        assert_equal("4", c.get("d"))
        s = c.stats()
        assert_equal(0, s.evictions, "no live entry evicted")
        assert_equal(1, s.expirations)

    @test("size counts only live entries; remove reports live removals")
    def size_and_remove(self):
        t = ManualTime()
        c = TtlLruCache(10, 100, t)
        c.put("a", "1")
        c.put("b", "2", 0)
        assert_equal(2, c.size())
        t.advance(100)
        assert_equal(1, c.size(), "a expired")
        assert_true(c.remove("b"))
        assert_false(c.remove("b"), "already gone")
        assert_false(c.remove("zzz"))
        assert_equal(0, c.size())

    @test("hits and misses are counted by get and compute_if_absent")
    def stats(self):
        c = TtlLruCache(10, 0, ManualTime())
        c.get("x")                                   # miss
        c.put("x", "1")
        c.get("x")                                   # hit
        c.compute_if_absent("y", lambda k: "loaded")  # miss
        c.compute_if_absent("y", lambda k: "never")   # hit
        s = c.stats()
        assert_equal(2, s.hits)
        assert_equal(2, s.misses)
        assert_equal("loaded", c.get("y"))

    @test("compute_if_absent caches results that aren't None only")
    def compute_none(self):
        c = TtlLruCache(10, 0, ManualTime())
        calls = []

        def loader(k):
            calls.append(k)
            return None

        assert_is_none(c.compute_if_absent("k", loader), "None is returned")
        c.compute_if_absent("k", loader)
        assert_equal(2, len(calls), "None must not be cached")
        assert_equal(0, c.size())

    @test("a failing loader caches nothing and the next call retries")
    def loader_failure(self):
        c = TtlLruCache(10, 0, ManualTime())

        def down(k):
            raise RuntimeError("backend down")

        try:
            c.compute_if_absent("k", down)
            raise AssertionError("expected RuntimeError")
        except RuntimeError as e:
            assert_equal("backend down", str(e), "the loader's own exception reaches the caller")
        assert_equal("ok", c.compute_if_absent("k", lambda k: "ok"), "retry after failure")

    @test("single flight: 16 concurrent misses run the loader once", timeout=15)
    def single_flight(self):
        c = TtlLruCache(10, 0, ManualTime())
        loads = []
        results = set()
        lock = threading.Lock()

        def loader(k):
            with lock:
                loads.append(k)
            real_time.sleep(0.15)
            return "price-42"

        def task(i):
            v = c.compute_if_absent("hot", loader)
            with lock:
                results.add(v)

        run_concurrently(16, task)
        assert_equal(1, len(loads), "the backend must be called exactly once")
        assert_equal({"price-42"}, results, "every caller gets the loaded value")

    @test("single flight: waiters receive the loader's exception", timeout=15)
    def single_flight_failure(self):
        c = TtlLruCache(10, 0, ManualTime())
        counts = {"loads": 0, "failures": 0}
        lock = threading.Lock()

        def loader(k):
            with lock:
                counts["loads"] += 1
            real_time.sleep(0.15)
            raise RuntimeError("boom")

        def task(i):
            try:
                c.compute_if_absent("bad", loader)
            except RuntimeError:
                with lock:
                    counts["failures"] += 1

        run_concurrently(8, task)
        assert_equal(1, counts["loads"], "one load for all callers")
        assert_equal(8, counts["failures"], "every caller sees the failure")

    @test("a slow load for one key does not block other keys")
    def no_global_lock_during_load(self):
        c = TtlLruCache(10, 0, ManualTime())
        c.put("fast", "1")

        def slow_loader(k):
            real_time.sleep(1.0)
            return "2"

        slow = threading.Thread(target=lambda: c.compute_if_absent("slow", slow_loader), daemon=True)
        slow.start()
        real_time.sleep(0.1)
        start = real_time.monotonic()
        assert_equal("1", c.get("fast"))
        ms = (real_time.monotonic() - start) * 1000
        assert_true(ms < 500, "get on another key waited %dms for the slow loader" % ms)
        slow.join(3)

    @test("validation")
    def validation(self):
        t = ManualTime()
        assert_raises(ValueError, lambda: TtlLruCache(0, 10, t))
        assert_raises(ValueError, lambda: TtlLruCache(1, 10, None))
        c = TtlLruCache(2, 10, t)
        assert_raises(ValueError, lambda: c.put(None, "v"))
        assert_raises(ValueError, lambda: c.put("k", None))
        assert_raises(ValueError, lambda: c.get(None))
        assert_raises(ValueError, lambda: c.compute_if_absent("k", None))

    @test("never exceeds capacity under concurrent use")
    def concurrent_capacity(self):
        c = TtlLruCache(20, 0, ManualTime())
        too_big = []

        def task(i):
            rnd = random.Random(i)
            for k in range(3_000):
                key = rnd.randrange(60)
                if rnd.random() < 0.5:
                    c.put(key, k)
                else:
                    c.get(key)
                if k % 100 == 0 and c.size() > 20:
                    too_big.append(c.size())

        run_concurrently(8, task)
        assert_equal([], too_big, "size exceeded capacity")
        assert_true(c.size() <= 20)
