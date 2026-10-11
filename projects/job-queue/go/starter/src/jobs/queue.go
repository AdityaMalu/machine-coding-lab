package jobs

import "errors"

var errTODO = errors.New("TODO") // placeholder so the starter compiles; replace every use

type Queue struct {
	// TODO
}

func NewQueue(clock Clock, policy RetryPolicy) (*Queue, error) {
	// TODO validate
	return &Queue{}, nil
}

// Submit adds a PENDING job and returns its id ("J1", "J2", ...).
func (q *Queue) Submit(jobType, payload string, priority int, runAtMillis int64) (string, error) {
	return "", errTODO
}

// Poll leases the best ready job to worker; ok is false if nothing is ready.
func (q *Queue) Poll(worker string, leaseMillis int64) (lease Lease, ok bool, err error) {
	// TODO process expired leases first, then lease the best ready job
	return Lease{}, false, errTODO
}

func (q *Queue) Complete(jobID, token string) error { return errTODO }

// Fail records the error ("" becomes "failed") and retries or dead-letters the job.
func (q *Queue) Fail(jobID, token, errMsg string) error { return errTODO }

func (q *Queue) View(jobID string) (JobView, error) { return JobView{}, errTODO }

func (q *Queue) DeadLetters() []JobView { return nil }

func (q *Queue) RetryDead(jobID string) error { return errTODO }

func (q *Queue) Cancel(jobID string) error { return errTODO }
