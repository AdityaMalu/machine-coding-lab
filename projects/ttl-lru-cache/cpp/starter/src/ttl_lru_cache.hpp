#pragma once

#include <functional>
#include <optional>
#include <stdexcept>

#include "cache_support.hpp"

template <typename K, typename V>
class TtlLruCache {
public:
    using Loader = std::function<std::optional<V>(const K&)>;

    // defaultTtlMillis <= 0 means entries never expire by default
    TtlLruCache(int capacity, int64_t defaultTtlMillis, const Clock& clock) : clock_(clock) {
        // TODO validate and set up storage (hint: std::list for recency order + unordered_map key -> iterator)
        (void)capacity;
        (void)defaultTtlMillis;
    }

    std::optional<V> get(const K& key) { throw std::logic_error("TODO"); }

    void put(const K& key, V value) { throw std::logic_error("TODO"); }

    // ttlMillis <= 0: never expires
    void put(const K& key, V value, int64_t ttlMillis) { throw std::logic_error("TODO"); }

    bool remove(const K& key) { throw std::logic_error("TODO"); }

    int size() { throw std::logic_error("TODO"); }

    // TODO single flight: concurrent callers for the same missing key share one load
    std::optional<V> computeIfAbsent(const K& key, const Loader& loader) { throw std::logic_error("TODO"); }

    CacheStats stats() { throw std::logic_error("TODO"); }

private:
    const Clock& clock_;
};
