package jobs

// Given.

import (
	"errors"
	"fmt"
)

// Clock is injected so tests control time.
type Clock interface{ NowMillis() int64 }

type Status string

const (
	StatusPending   Status = "PENDING"
	StatusRunning   Status = "RUNNING"
	StatusSucceeded Status = "SUCCEEDED"
	StatusDead      Status = "DEAD"
	StatusCancelled Status = "CANCELLED"
)

var (
	ErrInvalidArgument = errors.New("invalid argument")
	ErrNotFound        = errors.New("job not found")
	// ErrInvalidState: wrong status for the operation, or a lease you don't hold.
	ErrInvalidState = errors.New("invalid state")
)

// RetryPolicy: how many times a job may run, and the exponential backoff between attempts.
type RetryPolicy struct {
	MaxAttempts       int
	BaseBackoffMillis int64
	MaxBackoffMillis  int64
}

func NewRetryPolicy(maxAttempts int, baseBackoffMillis, maxBackoffMillis int64) (RetryPolicy, error) {
	switch {
	case maxAttempts < 1:
		return RetryPolicy{}, fmt.Errorf("maxAttempts must be >= 1: %w", ErrInvalidArgument)
	case baseBackoffMillis <= 0:
		return RetryPolicy{}, fmt.Errorf("baseBackoffMillis must be > 0: %w", ErrInvalidArgument)
	case maxBackoffMillis < baseBackoffMillis:
		return RetryPolicy{}, fmt.Errorf("maxBackoffMillis < baseBackoffMillis: %w", ErrInvalidArgument)
	}
	return RetryPolicy{maxAttempts, baseBackoffMillis, maxBackoffMillis}, nil
}

// Lease is a job handed to one worker. Only the holder of Token may complete or fail it.
type Lease struct {
	JobID, Token, Type, Payload string
	Attempt                     int
	LeaseUntil                  int64
}

type JobView struct {
	ID, Type  string
	Priority  int
	Status    Status
	Attempts  int
	NextRunAt int64
	LastError string // "" until the first failure
}
