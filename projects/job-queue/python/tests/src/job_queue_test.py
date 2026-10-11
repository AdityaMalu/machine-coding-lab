import threading

from jobs import InvalidStateError, JobQueue, JobStatus, RetryPolicy
from labtest import (assert_equal, assert_is_not_none, assert_is_none, assert_not_equal, assert_raises, assert_true,
                     run_concurrently, test)


class ManualTime:
    def __init__(self):
        self.now = 100_000
        self._lock = threading.Lock()

    def now_millis(self):
        with self._lock:
            return self.now

    def advance(self, ms):
        with self._lock:
            self.now += ms


POLICY = RetryPolicy(3, 1_000, 3_000)


def take(q, worker="w1", lease=30_000):
    lease_obj = q.poll(worker, lease)
    assert_is_not_none(lease_obj, "expected a ready job")
    return lease_obj


class JobQueueTest:

    @test("submit then poll hands out a running lease")
    def basic_lease(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        job_id = q.submit("email", "to=ana", 0, t.now_millis())
        assert_equal("J1", job_id)
        lease = take(q)
        assert_equal("J1", lease.job_id)
        assert_equal("email", lease.type)
        assert_equal("to=ana", lease.payload)
        assert_equal(1, lease.attempt)
        assert_equal(t.now_millis() + 30_000, lease.lease_until)
        v = q.view(job_id)
        assert_equal(JobStatus.RUNNING, v.status)
        assert_equal(1, v.attempts)
        assert_is_none(q.poll("w2", 30_000), "a running job is not handed out again")

    @test("jobs scheduled in the future are not ready yet")
    def run_at(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        q.submit("report", None, 0, t.now_millis() + 5_000)
        assert_is_none(q.poll("w", 1_000), "not ready")
        t.advance(4_999)
        assert_is_none(q.poll("w", 1_000), "1ms early")
        t.advance(1)
        lease = take(q)
        assert_equal("", lease.payload, "None payload stored as empty")

    @test("best job: priority, then earliest run_at, then submission order")
    def ordering(self):
        t = ManualTime()
        now = t.now_millis()
        q = JobQueue(t, POLICY)
        low_old = q.submit("x", "", 1, now - 500)
        high_late = q.submit("x", "", 5, now - 100)
        high_early = q.submit("x", "", 5, now - 200)
        high_early_twin = q.submit("x", "", 5, now - 200)
        assert_equal(high_early, take(q).job_id)
        assert_equal(high_early_twin, take(q).job_id)
        assert_equal(high_late, take(q).job_id)
        assert_equal(low_old, take(q).job_id)

    @test("complete marks the job succeeded")
    def complete(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        q.submit("x", "", 0, t.now_millis())
        lease = take(q)
        q.complete(lease.job_id, lease.token)
        assert_equal(JobStatus.SUCCEEDED, q.view(lease.job_id).status)
        assert_is_none(q.poll("w", 1_000))
        assert_raises(InvalidStateError, lambda: q.complete(lease.job_id, lease.token), "can't complete twice")

    @test("failures retry with exponential backoff, capped")
    def backoff(self):
        t = ManualTime()
        q = JobQueue(t, RetryPolicy(5, 1_000, 3_000))
        job_id = q.submit("x", "", 0, t.now_millis())
        expected = [1_000, 2_000, 3_000, 3_000]  # capped at 3000
        for attempt in range(1, 5):
            lease = take(q)
            assert_equal(attempt, lease.attempt, "attempt number")
            q.fail(job_id, lease.token, "smtp timeout")
            v = q.view(job_id)
            assert_equal(JobStatus.PENDING, v.status)
            assert_equal("smtp timeout", v.last_error)
            assert_equal(t.now_millis() + expected[attempt - 1], v.next_run_at, "backoff after attempt %d" % attempt)
            t.advance(expected[attempt - 1] - 1)
            assert_is_none(q.poll("w", 1_000), "not ready 1ms before backoff ends")
            t.advance(1)

    @test("after max_attempts the job is dead-lettered")
    def dead_letter(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)  # 3 attempts
        job_id = q.submit("invoice", "", 0, t.now_millis())
        q.submit("other", "", 0, t.now_millis() + 1_000_000)
        for i in range(3):
            lease = take(q)
            q.fail(job_id, lease.token, "final error" if i == 2 else None)
            t.advance(10_000)
        v = q.view(job_id)
        assert_equal(JobStatus.DEAD, v.status)
        assert_equal(3, v.attempts)
        assert_equal("final error", v.last_error)
        dead = q.dead_letters()
        assert_equal([job_id], [d.id for d in dead])
        assert_is_none(q.poll("w", 1_000), "dead jobs never run")

    @test("fail without a message records \"failed\"")
    def default_error(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        job_id = q.submit("x", "", 0, t.now_millis())
        lease = take(q)
        q.fail(job_id, lease.token)
        assert_equal("failed", q.view(job_id).last_error)

    @test("retry_dead revives a dead job with a fresh attempt budget")
    def retry_dead(self):
        t = ManualTime()
        q = JobQueue(t, RetryPolicy(1, 1_000, 1_000))
        job_id = q.submit("x", "", 0, t.now_millis())
        lease = take(q)
        q.fail(job_id, lease.token, "boom")
        assert_equal(JobStatus.DEAD, q.view(job_id).status)
        q.retry_dead(job_id)
        v = q.view(job_id)
        assert_equal(JobStatus.PENDING, v.status)
        assert_equal(0, v.attempts)
        assert_equal(1, take(q).attempt, "ready immediately")
        assert_raises(InvalidStateError, lambda: q.retry_dead(job_id), "only DEAD jobs")

    @test("an expired lease counts as a failed attempt and frees the job")
    def lease_expiry(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        job_id = q.submit("resize", "", 0, t.now_millis())
        first = take(q, "w1", 5_000)
        expired_at = first.lease_until
        t.advance(4_999)
        assert_is_none(q.poll("w2", 5_000), "lease still valid")
        t.advance(1)  # lease ends now
        v = q.view(job_id)
        assert_equal(JobStatus.PENDING, v.status)
        assert_equal("lease expired", v.last_error)
        assert_equal(expired_at + 1_000, v.next_run_at, "backoff counted from when the lease ran out")
        t.advance(1_000)
        second = take(q, "w2", 5_000)
        assert_equal(2, second.attempt)
        assert_not_equal(first.token, second.token, "every lease gets a new token")

    @test("a zombie worker can't complete or fail a job that was re-leased")
    def zombie_worker(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        job_id = q.submit("x", "", 0, t.now_millis())
        zombie = take(q, "w1", 1_000)
        t.advance(1_000 + 1_000)  # lease expired, backoff elapsed
        fresh = take(q, "w2", 60_000)
        assert_raises(InvalidStateError, lambda: q.complete(job_id, zombie.token), "stale token")
        assert_raises(InvalidStateError, lambda: q.fail(job_id, zombie.token, "x"), "stale token")
        assert_equal(JobStatus.RUNNING, q.view(job_id).status, "zombie did not change anything")
        q.complete(job_id, fresh.token)
        assert_equal(JobStatus.SUCCEEDED, q.view(job_id).status)

    @test("completing after your own lease expired is rejected")
    def late_complete(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        job_id = q.submit("x", "", 0, t.now_millis())
        lease = take(q, "w1", 1_000)
        t.advance(1_000)
        assert_raises(InvalidStateError, lambda: q.complete(job_id, lease.token))
        assert_equal("lease expired", q.view(job_id).last_error)

    @test("wrong token, unknown job, cancel rules")
    def errors(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        a = q.submit("x", "", 0, t.now_millis())
        b = q.submit("x", "", 0, t.now_millis() + 60_000)
        lease = take(q)
        assert_raises(InvalidStateError, lambda: q.complete(a, "made-up"))
        assert_raises(KeyError, lambda: q.view("J99"))
        assert_raises(InvalidStateError, lambda: q.cancel(a), "running job can't be cancelled")
        q.cancel(b)
        assert_equal(JobStatus.CANCELLED, q.view(b).status)
        q.complete(a, lease.token)
        t.advance(60_000)
        assert_is_none(q.poll("w", 1_000), "cancelled job never runs")

    @test("validation")
    def validation(self):
        t = ManualTime()
        assert_raises(ValueError, lambda: RetryPolicy(0, 1, 1))
        assert_raises(ValueError, lambda: RetryPolicy(1, 0, 1))
        assert_raises(ValueError, lambda: RetryPolicy(1, 10, 5))
        q = JobQueue(t, POLICY)
        assert_raises(ValueError, lambda: q.submit(" ", "", 0, 0))
        assert_raises(ValueError, lambda: q.poll("", 1_000))
        assert_raises(ValueError, lambda: q.poll("w", 0))

    @test("16 workers: every job is leased once and completed once", timeout=30)
    def concurrent_workers(self):
        t = ManualTime()
        q = JobQueue(t, POLICY)
        for i in range(300):
            q.submit("x", "p%d" % i, i % 7, t.now_millis())
        leased = []
        lock = threading.Lock()

        def worker(w):
            while True:
                lease = q.poll("worker-%d" % w, 60_000)
                if lease is None:
                    return
                with lock:
                    leased.append(lease.job_id)
                q.complete(lease.job_id, lease.token)

        run_concurrently(16, worker)
        assert_equal(300, len(leased))
        assert_equal(300, len(set(leased)), "a job was leased twice")
        for i in range(1, 301):
            assert_equal(JobStatus.SUCCEEDED, q.view("J%d" % i).status)
