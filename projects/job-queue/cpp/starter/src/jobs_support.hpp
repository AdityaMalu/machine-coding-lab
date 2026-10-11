#pragma once
// Given: clock, statuses, retry policy, the lease and view records, and the exception for wrong states.

#include <cstdint>
#include <optional>
#include <ostream>
#include <stdexcept>
#include <string>

struct Clock {
    virtual ~Clock() = default;
    virtual int64_t nowMillis() const = 0;
};

enum class JobStatus { Pending, Running, Succeeded, Dead, Cancelled };

inline const char* toString(JobStatus s) {
    switch (s) {
        case JobStatus::Pending: return "PENDING";
        case JobStatus::Running: return "RUNNING";
        case JobStatus::Succeeded: return "SUCCEEDED";
        case JobStatus::Dead: return "DEAD";
        case JobStatus::Cancelled: return "CANCELLED";
    }
    return "?";
}

inline std::ostream& operator<<(std::ostream& os, JobStatus s) { return os << toString(s); }

/** Wrong status for the operation, or a lease you don't hold. */
struct InvalidState : std::runtime_error {
    using std::runtime_error::runtime_error;
};

/** How many times a job may run, and the exponential backoff between attempts. */
struct RetryPolicy {
    int maxAttempts;
    int64_t baseBackoffMillis;
    int64_t maxBackoffMillis;

    RetryPolicy(int maxAttempts, int64_t baseBackoffMillis, int64_t maxBackoffMillis)
        : maxAttempts(maxAttempts), baseBackoffMillis(baseBackoffMillis), maxBackoffMillis(maxBackoffMillis) {
        if (maxAttempts < 1) throw std::invalid_argument("maxAttempts must be >= 1");
        if (baseBackoffMillis <= 0) throw std::invalid_argument("baseBackoffMillis must be > 0");
        if (maxBackoffMillis < baseBackoffMillis) throw std::invalid_argument("maxBackoffMillis < baseBackoffMillis");
    }
};

/** A job handed to one worker. Only the holder of `token` may complete or fail it. */
struct Lease {
    std::string jobId, token, type, payload;
    int attempt;
    int64_t leaseUntil;
};

struct JobView {
    std::string id, type;
    int priority;
    JobStatus status;
    int attempts;
    int64_t nextRunAt;
    std::optional<std::string> lastError;  // empty until the first failure
};
