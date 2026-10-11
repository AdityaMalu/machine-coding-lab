"""Given."""

from dataclasses import dataclass
from enum import Enum


class JobStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    DEAD = "DEAD"
    CANCELLED = "CANCELLED"


class InvalidStateError(RuntimeError):
    """The job isn't in a state that allows this (wrong status, or a lease you don't hold)."""


@dataclass(frozen=True)
class RetryPolicy:
    """How many times a job may run, and the exponential backoff between attempts."""
    max_attempts: int
    base_backoff_millis: int
    max_backoff_millis: int

    def __post_init__(self):
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        if self.base_backoff_millis <= 0:
            raise ValueError("base_backoff_millis must be > 0")
        if self.max_backoff_millis < self.base_backoff_millis:
            raise ValueError("max_backoff_millis < base_backoff_millis")


@dataclass(frozen=True)
class Lease:
    """A job handed to one worker. Only the holder of `token` may complete or fail it."""
    job_id: str
    token: str
    type: str
    payload: str
    attempt: int
    lease_until: int


@dataclass(frozen=True)
class JobView:
    id: str
    type: str
    priority: int
    status: JobStatus
    attempts: int
    next_run_at: int
    last_error: str | None
