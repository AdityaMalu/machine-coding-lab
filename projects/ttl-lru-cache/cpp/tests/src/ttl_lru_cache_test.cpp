#include <atomic>
#include <chrono>
#include <random>
#include <set>
#include <string>
#include <thread>

#include "labtest.hpp"
#include "ttl_lru_cache.hpp"

namespace {

struct ManualClock : Clock {
    std::atomic<int64_t> now{1000};
    int64_t nowMillis() const override { return now.load(); }
    void advance(int64_t ms) { now += ms; }
};

using StrCache = TtlLruCache<std::string, std::string>;
const std::optional<std::string> none = std::nullopt;

std::optional<std::string> some(const char* s) { return std::string(s); }

}  // namespace

LAB_TEST(TtlLruCacheTest, basics, "put then get; missing keys return nullopt") {
    ManualClock t;
    StrCache c(3, 0, t);
    c.put("a", "1");
    ASSERT_EQ(some("1"), c.get("a"), "after put");
    ASSERT_EQ(none, c.get("nope"), "missing key");
    c.put("a", "2");
    ASSERT_EQ(some("2"), c.get("a"), "put replaces");
    ASSERT_EQ(1, c.size(), "size");
}

LAB_TEST(TtlLruCacheTest, lru, "evicts the least recently used entry; get refreshes recency") {
    ManualClock t;
    StrCache c(3, 0, t);
    c.put("a", "1");
    c.put("b", "2");
    c.put("c", "3");
    c.get("a");       // order is now b, c, a
    c.put("d", "4");  // evicts b
    ASSERT_EQ(none, c.get("b"), "b was least recently used");
    ASSERT_EQ(some("1"), c.get("a"), "a");
    ASSERT_EQ(some("3"), c.get("c"), "c");
    ASSERT_EQ(some("4"), c.get("d"), "d");
    ASSERT_EQ(int64_t{1}, c.stats().evictions, "evictions");
}

LAB_TEST(TtlLruCacheTest, replaceRefreshes, "replacing a key refreshes recency and never evicts") {
    ManualClock t;
    StrCache c(2, 0, t);
    c.put("a", "1");
    c.put("b", "2");
    c.put("a", "1b");  // a becomes most recent, no eviction
    ASSERT_EQ(int64_t{0}, c.stats().evictions, "replace must not evict");
    c.put("c", "3");  // evicts b, not a
    ASSERT_EQ(none, c.get("b"), "b evicted");
    ASSERT_EQ(some("1b"), c.get("a"), "a kept");
}

LAB_TEST(TtlLruCacheTest, expiryBoundary, "expiry boundary: live until t+ttl, expired exactly at t+ttl") {
    ManualClock t;
    StrCache c(10, 500, t);
    c.put("k", "v");
    t.advance(499);
    ASSERT_EQ(some("v"), c.get("k"), "1ms before expiry");
    t.advance(1);
    ASSERT_EQ(none, c.get("k"), "exactly at expiry");
    ASSERT_EQ(int64_t{1}, c.stats().expirations, "expirations");
    ASSERT_EQ(0, c.size(), "size");
}

LAB_TEST(TtlLruCacheTest, getDoesNotExtend, "get does not extend the TTL") {
    ManualClock t;
    StrCache c(10, 1000, t);
    c.put("k", "v");
    for (int i = 0; i < 9; i++) {
        t.advance(100);
        ASSERT_EQ(some("v"), c.get("k"), "still live");
    }
    t.advance(100);
    ASSERT_EQ(none, c.get("k"), "expires 1000ms after the write, not after the last read");
}

LAB_TEST(TtlLruCacheTest, perEntryTtl, "per-entry TTL; ttl <= 0 never expires") {
    ManualClock t;
    StrCache c(10, 1000, t);
    c.put("short", "s", 100);
    c.put("forever", "f", 0);
    c.put("default", "d");
    t.advance(100);
    ASSERT_EQ(none, c.get("short"), "short TTL");
    ASSERT_EQ(some("d"), c.get("default"), "default TTL");
    t.advance(1'000'000'000);
    ASSERT_EQ(some("f"), c.get("forever"), "ttl 0 never expires");
    ASSERT_EQ(none, c.get("default"), "default TTL long gone");
}

LAB_TEST(TtlLruCacheTest, replaceResetsTtl, "replacing a key resets its TTL") {
    ManualClock t;
    StrCache c(10, 1000, t);
    c.put("k", "v1");
    t.advance(900);
    c.put("k", "v2");
    t.advance(900);
    ASSERT_EQ(some("v2"), c.get("k"), "new TTL started at the replace");
}

LAB_TEST(TtlLruCacheTest, expiredBeforeLru, "a full cache drops expired entries before evicting live ones") {
    ManualClock t;
    StrCache c(3, 10'000, t);
    c.put("a", "1", 100);
    c.put("b", "2");
    c.put("c", "3");
    t.advance(50);
    ASSERT_EQ(some("1"), c.get("a"), "a is now most recently used...");
    t.advance(150);  // ...but expired
    c.put("d", "4");
    ASSERT_EQ(some("2"), c.get("b"), "b must survive: an expired entry was available");
    ASSERT_EQ(some("3"), c.get("c"), "c");
    ASSERT_EQ(some("4"), c.get("d"), "d");
    CacheStats s = c.stats();
    ASSERT_EQ(int64_t{0}, s.evictions, "no live entry evicted");
    ASSERT_EQ(int64_t{1}, s.expirations, "expirations");
}

LAB_TEST(TtlLruCacheTest, sizeAndRemove, "size counts only live entries; remove reports live removals") {
    ManualClock t;
    StrCache c(10, 100, t);
    c.put("a", "1");
    c.put("b", "2", 0);
    ASSERT_EQ(2, c.size(), "size");
    t.advance(100);
    ASSERT_EQ(1, c.size(), "a expired");
    ASSERT_TRUE(c.remove("b"), "live removal");
    ASSERT_FALSE(c.remove("b"), "already gone");
    ASSERT_FALSE(c.remove("zzz"), "never existed");
    ASSERT_EQ(0, c.size(), "empty");
}

LAB_TEST(TtlLruCacheTest, stats, "hits and misses are counted by get and computeIfAbsent") {
    ManualClock t;
    StrCache c(10, 0, t);
    c.get("x");  // miss
    c.put("x", "1");
    c.get("x");                                                             // hit
    c.computeIfAbsent("y", [](const std::string&) { return some("loaded"); });  // miss
    c.computeIfAbsent("y", [](const std::string&) { return some("never"); });   // hit
    CacheStats s = c.stats();
    ASSERT_EQ(int64_t{2}, s.hits, "hits");
    ASSERT_EQ(int64_t{2}, s.misses, "misses");
    ASSERT_EQ(some("loaded"), c.get("y"), "loaded value cached");
}

LAB_TEST(TtlLruCacheTest, computeEmpty, "computeIfAbsent caches values only, never nullopt") {
    ManualClock t;
    StrCache c(10, 0, t);
    std::atomic<int> calls{0};
    auto loader = [&](const std::string&) -> std::optional<std::string> {
        calls++;
        return std::nullopt;
    };
    ASSERT_EQ(none, c.computeIfAbsent("k", loader), "nullopt is returned");
    c.computeIfAbsent("k", loader);
    ASSERT_EQ(2, calls.load(), "nullopt must not be cached");
    ASSERT_EQ(0, c.size(), "nothing cached");
}

LAB_TEST(TtlLruCacheTest, loaderFailure, "a failing loader caches nothing and the next call retries") {
    ManualClock t;
    StrCache c(10, 0, t);
    std::string message;
    try {
        c.computeIfAbsent("k", [](const std::string&) -> std::optional<std::string> {
            throw std::runtime_error("backend down");
        });
        FAIL("expected the loader's exception");
    } catch (const std::runtime_error& e) {
        message = e.what();
    }
    ASSERT_EQ(std::string("backend down"), message, "the loader's own exception reaches the caller");
    ASSERT_EQ(some("ok"), c.computeIfAbsent("k", [](const std::string&) { return some("ok"); }), "retry after failure");
}

LAB_TEST_TIMEOUT(TtlLruCacheTest, singleFlight, "single flight: 16 concurrent misses run the loader once", 15000) {
    ManualClock t;
    StrCache c(10, 0, t);
    std::atomic<int> loads{0};
    std::mutex mu;
    std::set<std::string> results;
    labtest::runConcurrently(16, [&](int) {
        auto v = c.computeIfAbsent("hot", [&](const std::string&) {
            loads++;
            std::this_thread::sleep_for(std::chrono::milliseconds(150));
            return some("price-42");
        });
        std::lock_guard<std::mutex> lock(mu);
        results.insert(v.value_or("<nullopt>"));
    });
    ASSERT_EQ(1, loads.load(), "the backend must be called exactly once");
    ASSERT_EQ(std::set<std::string>{"price-42"}, results, "every caller gets the loaded value");
}

LAB_TEST_TIMEOUT(TtlLruCacheTest, singleFlightFailure, "single flight: waiters receive the loader's exception", 15000) {
    ManualClock t;
    StrCache c(10, 0, t);
    std::atomic<int> loads{0}, failures{0};
    labtest::runConcurrently(8, [&](int) {
        try {
            c.computeIfAbsent("bad", [&](const std::string&) -> std::optional<std::string> {
                loads++;
                std::this_thread::sleep_for(std::chrono::milliseconds(150));
                throw std::runtime_error("boom");
            });
        } catch (const std::runtime_error&) {
            failures++;
        }
    });
    ASSERT_EQ(1, loads.load(), "one load for all callers");
    ASSERT_EQ(8, failures.load(), "every caller sees the failure");
}

LAB_TEST(TtlLruCacheTest, noGlobalLockDuringLoad, "a slow load for one key does not block other keys") {
    ManualClock t;
    StrCache c(10, 0, t);
    c.put("fast", "1");
    std::thread slow([&] {
        try {
            c.computeIfAbsent("slow", [](const std::string&) {
                std::this_thread::sleep_for(std::chrono::seconds(1));
                return some("2");
            });
        } catch (...) {
        }
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    auto start = std::chrono::steady_clock::now();
    std::optional<std::string> fast;
    try {
        fast = c.get("fast");
    } catch (...) {
        slow.join();
        throw;
    }
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    slow.join();
    ASSERT_EQ(some("1"), fast, "other key");
    ASSERT_TRUE(ms < 500, "get on another key waited " + std::to_string(ms) + "ms for the slow loader");
}

LAB_TEST(TtlLruCacheTest, validation, "validation") {
    ManualClock t;
    ASSERT_THROWS(std::invalid_argument, (StrCache(0, 10, t)), "capacity 0");
    StrCache c(2, 10, t);
    ASSERT_THROWS(std::invalid_argument, c.computeIfAbsent("k", StrCache::Loader{}), "empty loader");
}

LAB_TEST(TtlLruCacheTest, concurrentCapacity, "never exceeds capacity under concurrent use") {
    ManualClock t;
    TtlLruCache<int, int> c(20, 0, t);
    std::atomic<int> tooBig{0};
    labtest::runConcurrently(8, [&](int i) {
        std::mt19937 rnd(i);
        for (int k = 0; k < 3000; k++) {
            int key = static_cast<int>(rnd() % 60);
            if (rnd() % 2) c.put(key, k);
            else c.get(key);
            if (k % 100 == 0 && c.size() > 20) tooBig++;
        }
    });
    ASSERT_EQ(0, tooBig.load(), "size exceeded capacity");
    ASSERT_TRUE(c.size() <= 20 && c.size() >= 1, "final size " + std::to_string(c.size()));
}
