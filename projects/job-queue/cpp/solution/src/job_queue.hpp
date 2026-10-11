#pragma once

#include <algorithm>
#include <cctype>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "jobs_support.hpp"

class JobQueue {
public:
    JobQueue(const Clock& clock, RetryPolicy policy) : clock_(clock), policy_(policy) {}

    // returns "J1", "J2", ...
    std::string submit(const std::string& type, const std::string& payload, int priority, int64_t runAtMillis) {
        if (blank(type)) throw std::invalid_argument("type required");
        std::lock_guard<std::mutex> lock(mu_);
        auto job = std::make_unique<Job>();
        job->seq = nextSeq_++;
        job->id = "J" + std::to_string(job->seq);
        job->type = type;
        job->payload = payload;
        job->priority = priority;
        job->runAt = runAtMillis;
        std::string id = job->id;
        order_.push_back(job.get());
        jobs_.emplace(id, std::move(job));
        return id;
    }

    std::optional<Lease> poll(const std::string& workerId, int64_t leaseMillis) {
        if (blank(workerId)) throw std::invalid_argument("workerId required");
        if (leaseMillis <= 0) throw std::invalid_argument("leaseMillis must be > 0");
        std::lock_guard<std::mutex> lock(mu_);
        int64_t now = clock_.nowMillis();
        reclaimExpired(now);
        Job* best = nullptr;
        for (Job* j : order_) {
            if (j->status == JobStatus::Pending && j->runAt <= now && (!best || better(*j, *best))) best = j;
        }
        if (!best) return std::nullopt;
        best->status = JobStatus::Running;
        best->attempts++;
        best->leaseToken = best->id + "-" + std::to_string(nextToken_++);
        best->leaseUntil = now + leaseMillis;
        return Lease{best->id, best->leaseToken, best->type, best->payload, best->attempts, best->leaseUntil};
    }

    void complete(const std::string& jobId, const std::string& token) {
        std::lock_guard<std::mutex> lock(mu_);
        Job& j = find(jobId);
        reclaimExpired(clock_.nowMillis());
        requireLease(j, token);
        j.status = JobStatus::Succeeded;
        j.leaseToken.clear();
    }

    // error defaults to "failed"
    void fail(const std::string& jobId, const std::string& token, const std::string& error = "failed") {
        std::lock_guard<std::mutex> lock(mu_);
        Job& j = find(jobId);
        int64_t now = clock_.nowMillis();
        reclaimExpired(now);
        requireLease(j, token);
        failAttempt(j, now, error);
    }

    JobView view(const std::string& jobId) {
        std::lock_guard<std::mutex> lock(mu_);
        reclaimExpired(clock_.nowMillis());
        return find(jobId).view();
    }

    std::vector<JobView> deadLetters() {
        std::lock_guard<std::mutex> lock(mu_);
        reclaimExpired(clock_.nowMillis());
        std::vector<JobView> out;
        for (Job* j : order_) {
            if (j->status == JobStatus::Dead) out.push_back(j->view());
        }
        return out;
    }

    void retryDead(const std::string& jobId) {
        std::lock_guard<std::mutex> lock(mu_);
        Job& j = find(jobId);
        if (j.status != JobStatus::Dead) throw InvalidState("job is " + std::string(toString(j.status)) + ", not DEAD");
        j.status = JobStatus::Pending;
        j.attempts = 0;
        j.runAt = clock_.nowMillis();
    }

    void cancel(const std::string& jobId) {
        std::lock_guard<std::mutex> lock(mu_);
        Job& j = find(jobId);
        if (j.status != JobStatus::Pending) throw InvalidState("only PENDING jobs can be cancelled");
        j.status = JobStatus::Cancelled;
    }

private:
    struct Job {
        std::string id;
        int64_t seq = 0;
        std::string type, payload;
        int priority = 0;
        JobStatus status = JobStatus::Pending;
        int attempts = 0;
        int64_t runAt = 0;
        std::optional<std::string> lastError;
        std::string leaseToken;
        int64_t leaseUntil = 0;

        JobView view() const { return JobView{id, type, priority, status, attempts, runAt, lastError}; }
    };

    static bool blank(const std::string& s) {
        return std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isspace(c); });
    }

    static bool better(const Job& a, const Job& b) {
        if (a.priority != b.priority) return a.priority > b.priority;
        if (a.runAt != b.runAt) return a.runAt < b.runAt;
        return a.seq < b.seq;
    }

    // ---- internals: call with mu_ held

    // A lease that ran out is a failed attempt that happened at leaseUntil.
    void reclaimExpired(int64_t now) {
        for (Job* j : order_) {
            if (j->status == JobStatus::Running && now >= j->leaseUntil) failAttempt(*j, j->leaseUntil, "lease expired");
        }
    }

    void failAttempt(Job& j, int64_t failedAt, const std::string& error) {
        j.lastError = error;
        j.leaseToken.clear();
        if (j.attempts >= policy_.maxAttempts) {
            j.status = JobStatus::Dead;
        } else {
            j.status = JobStatus::Pending;
            j.runAt = failedAt + backoff(j.attempts);
        }
    }

    // min(max, base * 2^(attempt-1)) without overflow
    int64_t backoff(int attempt) const {
        int64_t b = policy_.baseBackoffMillis;
        for (int i = 1; i < attempt && b < policy_.maxBackoffMillis; i++) {
            b = b > std::numeric_limits<int64_t>::max() / 2 ? std::numeric_limits<int64_t>::max() : b * 2;
        }
        return std::min(b, policy_.maxBackoffMillis);
    }

    static void requireLease(const Job& j, const std::string& token) {
        if (j.status != JobStatus::Running || token.empty() || token != j.leaseToken) {
            throw InvalidState("lease not held for " + j.id + " (job is " + toString(j.status) + ")");
        }
    }

    Job& find(const std::string& jobId) {
        auto it = jobs_.find(jobId);
        if (it == jobs_.end()) throw std::out_of_range("no job " + jobId);
        return *it->second;
    }

    const Clock& clock_;
    RetryPolicy policy_;
    std::mutex mu_;
    std::map<std::string, std::unique_ptr<Job>> jobs_;
    std::vector<Job*> order_;  // submission order
    int64_t nextSeq_ = 1;
    int64_t nextToken_ = 1;
};
