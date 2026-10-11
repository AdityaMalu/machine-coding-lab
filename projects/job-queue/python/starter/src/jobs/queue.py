from .models import InvalidStateError, JobStatus, JobView, Lease, RetryPolicy


class JobQueue:
    def __init__(self, time, policy):
        # TODO
        pass

    def submit(self, type, payload, priority, run_at_millis):
        raise NotImplementedError("TODO")

    def poll(self, worker_id, lease_millis):
        # TODO process expired leases first, then lease the best ready job (or return None)
        raise NotImplementedError("TODO")

    def complete(self, job_id, token):
        raise NotImplementedError("TODO")

    def fail(self, job_id, token, error=None):
        raise NotImplementedError("TODO")

    def view(self, job_id):
        raise NotImplementedError("TODO")

    def dead_letters(self):
        raise NotImplementedError("TODO")

    def retry_dead(self, job_id):
        raise NotImplementedError("TODO")

    def cancel(self, job_id):
        raise NotImplementedError("TODO")
