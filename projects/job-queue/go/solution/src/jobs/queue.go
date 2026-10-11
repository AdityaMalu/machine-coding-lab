package jobs

import (
	"fmt"
	"math"
	"strings"
	"sync"
)

type job struct {
	id         string
	seq        int64
	jobType    string
	payload    string
	priority   int
	status     Status
	attempts   int
	runAt      int64
	lastError  string
	leaseToken string
	leaseUntil int64
}

func (j *job) view() JobView {
	return JobView{ID: j.id, Type: j.jobType, Priority: j.priority, Status: j.status, Attempts: j.attempts,
		NextRunAt: j.runAt, LastError: j.lastError}
}

type Queue struct {
	mu        sync.Mutex
	clock     Clock
	policy    RetryPolicy
	jobs      map[string]*job
	order     []*job // submission order
	nextSeq   int64
	nextToken int64
}

func NewQueue(clock Clock, policy RetryPolicy) (*Queue, error) {
	if clock == nil {
		return nil, fmt.Errorf("clock required: %w", ErrInvalidArgument)
	}
	if policy.MaxAttempts < 1 || policy.BaseBackoffMillis <= 0 || policy.MaxBackoffMillis < policy.BaseBackoffMillis {
		return nil, fmt.Errorf("use NewRetryPolicy for a valid policy: %w", ErrInvalidArgument)
	}
	return &Queue{clock: clock, policy: policy, jobs: map[string]*job{}, nextSeq: 1, nextToken: 1}, nil
}

// Submit adds a PENDING job and returns its id ("J1", "J2", ...).
func (q *Queue) Submit(jobType, payload string, priority int, runAtMillis int64) (string, error) {
	if strings.TrimSpace(jobType) == "" {
		return "", fmt.Errorf("type required: %w", ErrInvalidArgument)
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	j := &job{id: fmt.Sprintf("J%d", q.nextSeq), seq: q.nextSeq, jobType: jobType, payload: payload,
		priority: priority, status: StatusPending, runAt: runAtMillis}
	q.nextSeq++
	q.jobs[j.id] = j
	q.order = append(q.order, j)
	return j.id, nil
}

// Poll leases the best ready job to worker; ok is false if nothing is ready.
func (q *Queue) Poll(worker string, leaseMillis int64) (Lease, bool, error) {
	if strings.TrimSpace(worker) == "" {
		return Lease{}, false, fmt.Errorf("worker required: %w", ErrInvalidArgument)
	}
	if leaseMillis <= 0 {
		return Lease{}, false, fmt.Errorf("leaseMillis must be > 0: %w", ErrInvalidArgument)
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	now := q.clock.NowMillis()
	q.reclaimExpired(now)
	var best *job
	for _, j := range q.order {
		if j.status == StatusPending && j.runAt <= now && (best == nil || better(j, best)) {
			best = j
		}
	}
	if best == nil {
		return Lease{}, false, nil
	}
	best.status = StatusRunning
	best.attempts++
	best.leaseToken = fmt.Sprintf("%s-%d", best.id, q.nextToken)
	q.nextToken++
	best.leaseUntil = now + leaseMillis
	return Lease{JobID: best.id, Token: best.leaseToken, Type: best.jobType, Payload: best.payload,
		Attempt: best.attempts, LeaseUntil: best.leaseUntil}, true, nil
}

func (q *Queue) Complete(jobID, token string) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	j, err := q.find(jobID)
	if err != nil {
		return err
	}
	q.reclaimExpired(q.clock.NowMillis())
	if err := requireLease(j, token); err != nil {
		return err
	}
	j.status = StatusSucceeded
	j.leaseToken = ""
	return nil
}

// Fail records the error ("" becomes "failed") and retries or dead-letters the job.
func (q *Queue) Fail(jobID, token, errMsg string) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	j, err := q.find(jobID)
	if err != nil {
		return err
	}
	now := q.clock.NowMillis()
	q.reclaimExpired(now)
	if err := requireLease(j, token); err != nil {
		return err
	}
	if errMsg == "" {
		errMsg = "failed"
	}
	q.failAttempt(j, now, errMsg)
	return nil
}

func (q *Queue) View(jobID string) (JobView, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.reclaimExpired(q.clock.NowMillis())
	j, err := q.find(jobID)
	if err != nil {
		return JobView{}, err
	}
	return j.view(), nil
}

func (q *Queue) DeadLetters() []JobView {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.reclaimExpired(q.clock.NowMillis())
	out := []JobView{}
	for _, j := range q.order {
		if j.status == StatusDead {
			out = append(out, j.view())
		}
	}
	return out
}

func (q *Queue) RetryDead(jobID string) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	j, err := q.find(jobID)
	if err != nil {
		return err
	}
	if j.status != StatusDead {
		return fmt.Errorf("job is %s, not DEAD: %w", j.status, ErrInvalidState)
	}
	j.status = StatusPending
	j.attempts = 0
	j.runAt = q.clock.NowMillis()
	return nil
}

func (q *Queue) Cancel(jobID string) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	j, err := q.find(jobID)
	if err != nil {
		return err
	}
	if j.status != StatusPending {
		return fmt.Errorf("only PENDING jobs can be cancelled: %w", ErrInvalidState)
	}
	j.status = StatusCancelled
	return nil
}

// ---------------------------------------------------------------- internals (call with mu held)

func better(a, b *job) bool {
	if a.priority != b.priority {
		return a.priority > b.priority
	}
	if a.runAt != b.runAt {
		return a.runAt < b.runAt
	}
	return a.seq < b.seq
}

// reclaimExpired: a lease that ran out is a failed attempt that happened at leaseUntil.
func (q *Queue) reclaimExpired(now int64) {
	for _, j := range q.order {
		if j.status == StatusRunning && now >= j.leaseUntil {
			q.failAttempt(j, j.leaseUntil, "lease expired")
		}
	}
}

func (q *Queue) failAttempt(j *job, failedAt int64, errMsg string) {
	j.lastError = errMsg
	j.leaseToken = ""
	if j.attempts >= q.policy.MaxAttempts {
		j.status = StatusDead
		return
	}
	j.status = StatusPending
	j.runAt = failedAt + q.backoff(j.attempts)
}

// backoff is min(max, base * 2^(attempt-1)) without overflow.
func (q *Queue) backoff(attempt int) int64 {
	b := q.policy.BaseBackoffMillis
	for i := 1; i < attempt && b < q.policy.MaxBackoffMillis; i++ {
		if b > math.MaxInt64/2 {
			b = math.MaxInt64
		} else {
			b *= 2
		}
	}
	return min(b, q.policy.MaxBackoffMillis)
}

func requireLease(j *job, token string) error {
	if j.status != StatusRunning || token == "" || token != j.leaseToken {
		return fmt.Errorf("lease not held for %s (job is %s): %w", j.id, j.status, ErrInvalidState)
	}
	return nil
}

func (q *Queue) find(jobID string) (*job, error) {
	j, ok := q.jobs[jobID]
	if !ok {
		return nil, fmt.Errorf("no job %q: %w", jobID, ErrNotFound)
	}
	return j, nil
}
