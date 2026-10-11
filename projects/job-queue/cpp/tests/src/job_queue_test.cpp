#include <atomic>
#include <map>
#include <mutex>
#include <string>

#include "job_queue.hpp"
#include "labtest.hpp"

namespace {

struct ManualClock : Clock {
    std::atomic<int64_t> now{100'000};
    int64_t nowMillis() const override { return now.load(); }
    void advance(int64_t ms) { now += ms; }
};

const RetryPolicy POLICY(3, 1000, 3000);

Lease take(JobQueue& q, const std::string& worker = "w1", int64_t lease = 30'000) {
    std::optional<Lease> l = q.poll(worker, lease);
    if (!l) FAIL("expected a ready job");
    return *l;
}

bool ready(JobQueue& q) { return q.poll("w", 1000).has_value(); }

}  // namespace

LAB_TEST(JobQueueTest, basicLease, "submit then poll hands out a running lease") {
    ManualClock t;
    JobQueue q(t, POLICY);
    std::string id = q.submit("email", "to=ana", 0, t.nowMillis());
    ASSERT_EQ(std::string("J1"), id, "first id");
    Lease l = take(q);
    ASSERT_EQ(std::string("J1"), l.jobId, "jobId");
    ASSERT_EQ(std::string("email"), l.type, "type");
    ASSERT_EQ(std::string("to=ana"), l.payload, "payload");
    ASSERT_EQ(1, l.attempt, "attempt");
    ASSERT_EQ(t.nowMillis() + 30'000, l.leaseUntil, "leaseUntil");
    JobView v = q.view(id);
    ASSERT_EQ(JobStatus::Running, v.status, "status");
    ASSERT_EQ(1, v.attempts, "attempts");
    ASSERT_FALSE(q.poll("w2", 30'000).has_value(), "a running job is not handed out again");
}

LAB_TEST(JobQueueTest, runAt, "jobs scheduled in the future are not ready yet") {
    ManualClock t;
    JobQueue q(t, POLICY);
    q.submit("report", "", 0, t.nowMillis() + 5000);
    ASSERT_FALSE(ready(q), "not ready");
    t.advance(4999);
    ASSERT_FALSE(ready(q), "1ms early");
    t.advance(1);
    take(q);
}

LAB_TEST(JobQueueTest, ordering, "best job: priority, then earliest runAt, then submission order") {
    ManualClock t;
    int64_t now = t.nowMillis();
    JobQueue q(t, POLICY);
    std::string lowOld = q.submit("x", "", 1, now - 500);
    std::string highLate = q.submit("x", "", 5, now - 100);
    std::string highEarly = q.submit("x", "", 5, now - 200);
    std::string highEarlyTwin = q.submit("x", "", 5, now - 200);
    ASSERT_EQ(highEarly, take(q).jobId, "1st");
    ASSERT_EQ(highEarlyTwin, take(q).jobId, "2nd");
    ASSERT_EQ(highLate, take(q).jobId, "3rd");
    ASSERT_EQ(lowOld, take(q).jobId, "4th");
}

LAB_TEST(JobQueueTest, complete, "complete marks the job succeeded") {
    ManualClock t;
    JobQueue q(t, POLICY);
    q.submit("x", "", 0, t.nowMillis());
    Lease l = take(q);
    q.complete(l.jobId, l.token);
    ASSERT_EQ(JobStatus::Succeeded, q.view(l.jobId).status, "status");
    ASSERT_FALSE(ready(q), "nothing left");
    ASSERT_THROWS(InvalidState, q.complete(l.jobId, l.token), "can't complete twice");
}

LAB_TEST(JobQueueTest, backoff, "failures retry with exponential backoff, capped") {
    ManualClock t;
    JobQueue q(t, RetryPolicy(5, 1000, 3000));
    std::string id = q.submit("x", "", 0, t.nowMillis());
    const int64_t expected[] = {1000, 2000, 3000, 3000};  // capped at 3000
    for (int attempt = 1; attempt <= 4; attempt++) {
        Lease l = take(q);
        ASSERT_EQ(attempt, l.attempt, "attempt number");
        q.fail(id, l.token, "smtp timeout");
        JobView v = q.view(id);
        ASSERT_EQ(JobStatus::Pending, v.status, "status");
        ASSERT_EQ(std::optional<std::string>("smtp timeout"), v.lastError, "lastError");
        ASSERT_EQ(t.nowMillis() + expected[attempt - 1], v.nextRunAt, "backoff after attempt " + std::to_string(attempt));
        t.advance(expected[attempt - 1] - 1);
        ASSERT_FALSE(ready(q), "not ready 1ms before backoff ends");
        t.advance(1);
    }
}

LAB_TEST(JobQueueTest, deadLetter, "after maxAttempts the job is dead-lettered") {
    ManualClock t;
    JobQueue q(t, POLICY);  // 3 attempts
    std::string id = q.submit("invoice", "", 0, t.nowMillis());
    q.submit("other", "", 0, t.nowMillis() + 1'000'000);
    for (int i = 0; i < 3; i++) {
        Lease l = take(q);
        if (i == 2) q.fail(id, l.token, "final error");
        else q.fail(id, l.token);
        t.advance(10'000);
    }
    JobView v = q.view(id);
    ASSERT_EQ(JobStatus::Dead, v.status, "status");
    ASSERT_EQ(3, v.attempts, "attempts");
    ASSERT_EQ(std::optional<std::string>("final error"), v.lastError, "lastError");
    std::vector<JobView> dead = q.deadLetters();
    ASSERT_EQ(size_t{1}, dead.size(), "one dead letter");
    ASSERT_EQ(id, dead[0].id, "dead letter id");
    ASSERT_FALSE(ready(q), "dead jobs never run");
}

LAB_TEST(JobQueueTest, defaultError, "fail without a message records \"failed\"") {
    ManualClock t;
    JobQueue q(t, POLICY);
    std::string id = q.submit("x", "", 0, t.nowMillis());
    Lease l = take(q);
    q.fail(id, l.token);
    ASSERT_EQ(std::optional<std::string>("failed"), q.view(id).lastError, "lastError");
}

LAB_TEST(JobQueueTest, retryDead, "retryDead revives a dead job with a fresh attempt budget") {
    ManualClock t;
    JobQueue q(t, RetryPolicy(1, 1000, 1000));
    std::string id = q.submit("x", "", 0, t.nowMillis());
    Lease l = take(q);
    q.fail(id, l.token, "boom");
    ASSERT_EQ(JobStatus::Dead, q.view(id).status, "dead");
    q.retryDead(id);
    JobView v = q.view(id);
    ASSERT_EQ(JobStatus::Pending, v.status, "pending again");
    ASSERT_EQ(0, v.attempts, "attempts reset");
    ASSERT_EQ(1, take(q).attempt, "ready immediately");
    ASSERT_THROWS(InvalidState, q.retryDead(id), "only DEAD jobs");
}

LAB_TEST(JobQueueTest, leaseExpiry, "an expired lease counts as a failed attempt and frees the job") {
    ManualClock t;
    JobQueue q(t, POLICY);
    std::string id = q.submit("resize", "", 0, t.nowMillis());
    Lease first = take(q, "w1", 5000);
    t.advance(4999);
    ASSERT_FALSE(q.poll("w2", 5000).has_value(), "lease still valid");
    t.advance(1);  // lease ends now
    JobView v = q.view(id);
    ASSERT_EQ(JobStatus::Pending, v.status, "status");
    ASSERT_EQ(std::optional<std::string>("lease expired"), v.lastError, "lastError");
    ASSERT_EQ(first.leaseUntil + 1000, v.nextRunAt, "backoff counted from when the lease ran out");
    t.advance(1000);
    Lease second = take(q, "w2", 5000);
    ASSERT_EQ(2, second.attempt, "second attempt");
    ASSERT_NE(first.token, second.token, "every lease gets a new token");
}

LAB_TEST(JobQueueTest, zombieWorker, "a zombie worker can't complete or fail a job that was re-leased") {
    ManualClock t;
    JobQueue q(t, POLICY);
    std::string id = q.submit("x", "", 0, t.nowMillis());
    Lease zombie = take(q, "w1", 1000);
    t.advance(1000 + 1000);  // lease expired, backoff elapsed
    Lease fresh = take(q, "w2", 60'000);
    ASSERT_THROWS(InvalidState, q.complete(id, zombie.token), "stale token");
    ASSERT_THROWS(InvalidState, q.fail(id, zombie.token, "x"), "stale token");
    ASSERT_EQ(JobStatus::Running, q.view(id).status, "zombie did not change anything");
    q.complete(id, fresh.token);
    ASSERT_EQ(JobStatus::Succeeded, q.view(id).status, "fresh token completes");
}

LAB_TEST(JobQueueTest, lateComplete, "completing after your own lease expired is rejected") {
    ManualClock t;
    JobQueue q(t, POLICY);
    std::string id = q.submit("x", "", 0, t.nowMillis());
    Lease l = take(q, "w1", 1000);
    t.advance(1000);
    ASSERT_THROWS(InvalidState, q.complete(id, l.token), "late complete");
    ASSERT_EQ(std::optional<std::string>("lease expired"), q.view(id).lastError, "lastError");
}

LAB_TEST(JobQueueTest, errors, "wrong token, unknown job, cancel rules") {
    ManualClock t;
    JobQueue q(t, POLICY);
    std::string a = q.submit("x", "", 0, t.nowMillis());
    std::string b = q.submit("x", "", 0, t.nowMillis() + 60'000);
    Lease l = take(q);
    ASSERT_THROWS(InvalidState, q.complete(a, "made-up"), "made-up token");
    ASSERT_THROWS(std::out_of_range, q.view("J99"), "unknown job");
    ASSERT_THROWS(InvalidState, q.cancel(a), "running job can't be cancelled");
    q.cancel(b);
    ASSERT_EQ(JobStatus::Cancelled, q.view(b).status, "cancelled");
    q.complete(a, l.token);
    t.advance(60'000);
    ASSERT_FALSE(ready(q), "cancelled job never runs");
}

LAB_TEST(JobQueueTest, validation, "validation") {
    ManualClock t;
    ASSERT_THROWS(std::invalid_argument, RetryPolicy(0, 1, 1), "maxAttempts 0");
    ASSERT_THROWS(std::invalid_argument, RetryPolicy(1, 0, 1), "base 0");
    ASSERT_THROWS(std::invalid_argument, RetryPolicy(1, 10, 5), "max < base");
    JobQueue q(t, POLICY);
    ASSERT_THROWS(std::invalid_argument, q.submit(" ", "", 0, 0), "blank type");
    ASSERT_THROWS(std::invalid_argument, q.poll("", 1000), "blank worker");
    ASSERT_THROWS(std::invalid_argument, q.poll("w", 0), "leaseMillis 0");
}

LAB_TEST_TIMEOUT(JobQueueTest, concurrentWorkers, "16 workers: every job is leased once and completed once", 20000) {
    ManualClock t;
    JobQueue q(t, POLICY);
    for (int i = 0; i < 300; i++) q.submit("x", "p" + std::to_string(i), i % 7, t.nowMillis());
    std::mutex mu;
    std::map<std::string, int> leased;
    labtest::runConcurrently(16, [&](int w) {
        while (true) {
            std::optional<Lease> l = q.poll("worker-" + std::to_string(w), 60'000);
            if (!l) break;
            {
                std::lock_guard<std::mutex> lock(mu);
                leased[l->jobId]++;
            }
            q.complete(l->jobId, l->token);
        }
    });
    ASSERT_EQ(size_t{300}, leased.size(), "distinct jobs leased");
    for (const auto& [id, n] : leased) ASSERT_EQ(1, n, "job " + id + " leased more than once");
    for (int i = 1; i <= 300; i++) {
        ASSERT_EQ(JobStatus::Succeeded, q.view("J" + std::to_string(i)).status, "J" + std::to_string(i));
    }
}
