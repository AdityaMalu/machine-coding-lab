import threading

from .models import InvalidStateError, JobStatus, JobView, Lease, RetryPolicy


class _Job:
    def __init__(self, id, seq, type, payload, priority, run_at):
        self.id = id
        self.seq = seq
        self.type = type
        self.payload = payload
        self.priority = priority
        self.status = JobStatus.PENDING
        self.attempts = 0
        self.run_at = run_at
        self.last_error = None
        self.lease_token = None
        self.lease_until = 0

    def view(self):
        return JobView(self.id, self.type, self.priority, self.status, self.attempts, self.run_at, self.last_error)


class JobQueue:
    def __init__(self, time, policy):
        if time is None or policy is None:
            raise ValueError("time and policy required")
        self._time = time
        self._policy = policy
        self._jobs = {}  # job id -> _Job, in submission order
        self._next_seq = 1
        self._next_token = 1
        self._lock = threading.Lock()

    def submit(self, type, payload, priority, run_at_millis):
        if type is None or not type.strip():
            raise ValueError("type required")
        with self._lock:
            seq = self._next_seq
            self._next_seq += 1
            job = _Job("J%d" % seq, seq, type, "" if payload is None else payload, priority, run_at_millis)
            self._jobs[job.id] = job
            return job.id

    def poll(self, worker_id, lease_millis):
        if worker_id is None or not worker_id.strip():
            raise ValueError("worker_id required")
        if lease_millis <= 0:
            raise ValueError("lease_millis must be > 0")
        with self._lock:
            now = self._time.now_millis()
            self._reclaim_expired(now)
            ready = [j for j in self._jobs.values() if j.status == JobStatus.PENDING and j.run_at <= now]
            if not ready:
                return None
            best = min(ready, key=lambda j: (-j.priority, j.run_at, j.seq))
            best.status = JobStatus.RUNNING
            best.attempts += 1
            best.lease_token = "%s-%d" % (best.id, self._next_token)
            self._next_token += 1
            best.lease_until = now + lease_millis
            return Lease(best.id, best.lease_token, best.type, best.payload, best.attempts, best.lease_until)

    def complete(self, job_id, token):
        with self._lock:
            job = self._find(job_id)
            self._reclaim_expired(self._time.now_millis())
            self._require_lease(job, token)
            job.status = JobStatus.SUCCEEDED
            job.lease_token = None

    def fail(self, job_id, token, error=None):
        with self._lock:
            job = self._find(job_id)
            now = self._time.now_millis()
            self._reclaim_expired(now)
            self._require_lease(job, token)
            self._fail_attempt(job, now, "failed" if error is None else error)

    def view(self, job_id):
        with self._lock:
            self._reclaim_expired(self._time.now_millis())
            return self._find(job_id).view()

    def dead_letters(self):
        with self._lock:
            self._reclaim_expired(self._time.now_millis())
            return [j.view() for j in self._jobs.values() if j.status == JobStatus.DEAD]

    def retry_dead(self, job_id):
        with self._lock:
            job = self._find(job_id)
            if job.status != JobStatus.DEAD:
                raise InvalidStateError("job is %s, not DEAD" % job.status.value)
            job.status = JobStatus.PENDING
            job.attempts = 0
            job.run_at = self._time.now_millis()

    def cancel(self, job_id):
        with self._lock:
            job = self._find(job_id)
            if job.status != JobStatus.PENDING:
                raise InvalidStateError("only PENDING jobs can be cancelled")
            job.status = JobStatus.CANCELLED

    # ---------------------------------------------------------------- internals (call with the lock held)

    def _reclaim_expired(self, now):
        """A lease that ran out is a failed attempt that happened at lease_until."""
        for job in self._jobs.values():
            if job.status == JobStatus.RUNNING and now >= job.lease_until:
                self._fail_attempt(job, job.lease_until, "lease expired")

    def _fail_attempt(self, job, failed_at, error):
        job.last_error = error
        job.lease_token = None
        if job.attempts >= self._policy.max_attempts:
            job.status = JobStatus.DEAD
        else:
            job.status = JobStatus.PENDING
            job.run_at = failed_at + self._backoff(job.attempts)

    def _backoff(self, attempt):
        """min(max, base * 2^(attempt-1)); Python ints don't overflow, but stop doubling at the cap anyway."""
        b = self._policy.base_backoff_millis
        for _ in range(1, attempt):
            if b >= self._policy.max_backoff_millis:
                break
            b *= 2
        return min(b, self._policy.max_backoff_millis)

    @staticmethod
    def _require_lease(job, token):
        if job.status != JobStatus.RUNNING or token is None or token != job.lease_token:
            raise InvalidStateError("lease not held for %s (job is %s)" % (job.id, job.status.value))

    def _find(self, job_id):
        job = self._jobs.get(job_id)
        if job is None:
            raise KeyError("no job %s" % job_id)
        return job
