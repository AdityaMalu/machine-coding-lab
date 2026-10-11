#pragma once

#include <optional>
#include <string>
#include <vector>

#include "jobs_support.hpp"

class JobQueue {
public:
    JobQueue(const Clock& clock, RetryPolicy policy) : clock_(clock), policy_(policy) {}

    // returns "J1", "J2", ...
    std::string submit(const std::string& type, const std::string& payload, int priority, int64_t runAtMillis) {
        throw std::logic_error("TODO");
    }

    // TODO process expired leases first, then lease the best ready job (or nullopt)
    std::optional<Lease> poll(const std::string& workerId, int64_t leaseMillis) { throw std::logic_error("TODO"); }

    void complete(const std::string& jobId, const std::string& token) { throw std::logic_error("TODO"); }

    // error defaults to "failed"
    void fail(const std::string& jobId, const std::string& token, const std::string& error = "failed") {
        throw std::logic_error("TODO");
    }

    JobView view(const std::string& jobId) { throw std::logic_error("TODO"); }

    std::vector<JobView> deadLetters() { throw std::logic_error("TODO"); }

    void retryDead(const std::string& jobId) { throw std::logic_error("TODO"); }

    void cancel(const std::string& jobId) { throw std::logic_error("TODO"); }

private:
    const Clock& clock_;
    RetryPolicy policy_;
};
