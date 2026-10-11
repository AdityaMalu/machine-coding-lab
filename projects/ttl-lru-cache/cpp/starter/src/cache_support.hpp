#pragma once
// Given: the clock and the stats record.

#include <cstdint>

struct Clock {
    virtual ~Clock() = default;
    virtual int64_t nowMillis() const = 0;
};

struct CacheStats {
    int64_t hits = 0, misses = 0, evictions = 0, expirations = 0;
};
