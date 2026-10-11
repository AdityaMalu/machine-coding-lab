#pragma once

#include <exception>
#include <functional>
#include <future>
#include <limits>
#include <list>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <unordered_map>
#include <utility>

#include "cache_support.hpp"

template <typename K, typename V>
class TtlLruCache {
public:
    using Loader = std::function<std::optional<V>(const K&)>;

    // defaultTtlMillis <= 0 means entries never expire by default
    TtlLruCache(int capacity, int64_t defaultTtlMillis, const Clock& clock)
        : capacity_(capacity), defaultTtl_(defaultTtlMillis), clock_(clock) {
        if (capacity < 1) throw std::invalid_argument("capacity must be >= 1");
    }

    std::optional<V> get(const K& key) {
        std::lock_guard<std::mutex> lock(mu_);
        if (Entry* e = live(key, clock_.nowMillis())) {
            stats_.hits++;
            return e->value;
        }
        stats_.misses++;
        return std::nullopt;
    }

    void put(const K& key, V value) { put(key, std::move(value), defaultTtl_); }

    // ttlMillis <= 0: never expires
    void put(const K& key, V value, int64_t ttlMillis) {
        std::lock_guard<std::mutex> lock(mu_);
        insert(key, std::move(value), ttlMillis);
    }

    bool remove(const K& key) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = index_.find(key);
        if (it == index_.end()) return false;
        bool expired = clock_.nowMillis() >= it->second->expiresAt;
        order_.erase(it->second);
        index_.erase(it);
        if (expired) stats_.expirations++;
        return !expired;
    }

    int size() {
        std::lock_guard<std::mutex> lock(mu_);
        purgeExpired(clock_.nowMillis());
        return static_cast<int>(index_.size());
    }

    std::optional<V> computeIfAbsent(const K& key, const Loader& loader) {
        if (!loader) throw std::invalid_argument("loader required");
        std::promise<std::optional<V>> promise;
        std::shared_future<std::optional<V>> theirs;
        {
            std::lock_guard<std::mutex> lock(mu_);
            if (Entry* e = live(key, clock_.nowMillis())) {
                stats_.hits++;
                return e->value;
            }
            stats_.misses++;
            auto it = loads_.find(key);
            if (it != loads_.end()) {
                theirs = it->second;
            } else {
                loads_.emplace(key, promise.get_future().share());
            }
        }
        if (theirs.valid()) return theirs.get();  // rethrows the loader's exception, if any

        // we are the loader; run it without holding the cache lock
        try {
            std::optional<V> value = loader(key);
            {
                std::lock_guard<std::mutex> lock(mu_);
                loads_.erase(key);
                if (value) insert(key, *value, defaultTtl_);
            }
            promise.set_value(value);
            return value;
        } catch (...) {
            {
                std::lock_guard<std::mutex> lock(mu_);
                loads_.erase(key);
            }
            promise.set_exception(std::current_exception());
            throw;
        }
    }

    CacheStats stats() {
        std::lock_guard<std::mutex> lock(mu_);
        return stats_;
    }

private:
    struct Entry {
        K key;
        V value;
        int64_t expiresAt;
    };

    // ---- internals: call with mu_ held

    void insert(const K& key, V value, int64_t ttl) {
        int64_t now = clock_.nowMillis();
        auto it = index_.find(key);
        if (it != index_.end()) {
            if (now >= it->second->expiresAt) stats_.expirations++;
            order_.erase(it->second);
            index_.erase(it);
        } else {
            makeRoom(now);
        }
        int64_t expires = std::numeric_limits<int64_t>::max();
        if (ttl > 0 && now <= std::numeric_limits<int64_t>::max() - ttl) expires = now + ttl;
        order_.push_front(Entry{key, std::move(value), expires});
        index_[key] = order_.begin();
    }

    // The entry for key, marked most recently used; removed (and nullptr) if expired.
    Entry* live(const K& key, int64_t now) {
        auto it = index_.find(key);
        if (it == index_.end()) return nullptr;
        if (now >= it->second->expiresAt) {
            order_.erase(it->second);
            index_.erase(it);
            stats_.expirations++;
            return nullptr;
        }
        order_.splice(order_.begin(), order_, it->second);
        return &*it->second;
    }

    void makeRoom(int64_t now) {
        if (static_cast<int>(index_.size()) < capacity_) return;
        purgeExpired(now);
        while (static_cast<int>(index_.size()) >= capacity_) {
            index_.erase(order_.back().key);
            order_.pop_back();
            stats_.evictions++;
        }
    }

    void purgeExpired(int64_t now) {
        for (auto it = order_.begin(); it != order_.end();) {
            if (now >= it->expiresAt) {
                index_.erase(it->key);
                it = order_.erase(it);
                stats_.expirations++;
            } else {
                ++it;
            }
        }
    }

    const int capacity_;
    const int64_t defaultTtl_;
    const Clock& clock_;
    std::mutex mu_;
    std::list<Entry> order_;  // front = most recently used
    std::unordered_map<K, typename std::list<Entry>::iterator> index_;
    std::unordered_map<K, std::shared_future<std::optional<V>>> loads_;
    CacheStats stats_;
};
