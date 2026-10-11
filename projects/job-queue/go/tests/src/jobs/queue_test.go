package jobs

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
)

type manualClock struct{ now atomic.Int64 }

func newClock() *manualClock {
	c := &manualClock{}
	c.now.Store(100_000)
	return c
}

func (c *manualClock) NowMillis() int64 { return c.now.Load() }
func (c *manualClock) advance(ms int64) { c.now.Add(ms) }

func policy(t *testing.T, max int, base, cap int64) RetryPolicy {
	t.Helper()
	p, err := NewRetryPolicy(max, base, cap)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func newQueue(t *testing.T, c Clock, p RetryPolicy) *Queue {
	t.Helper()
	q, err := NewQueue(c, p)
	if err != nil || q == nil {
		t.Fatalf("NewQueue failed: %v", err)
	}
	return q
}

func defaultQueue(t *testing.T, c Clock) *Queue { return newQueue(t, c, policy(t, 3, 1_000, 3_000)) }

func submit(t *testing.T, q *Queue, typ string, priority int, runAt int64) string {
	t.Helper()
	id, err := q.Submit(typ, "", priority, runAt)
	if err != nil {
		t.Fatalf("Submit(%q) failed: %v", typ, err)
	}
	return id
}

func take(t *testing.T, q *Queue, worker string, leaseMillis int64) Lease {
	t.Helper()
	l, ok, err := q.Poll(worker, leaseMillis)
	if err != nil || !ok {
		t.Fatalf("expected a ready job, got ok=%v err=%v", ok, err)
	}
	return l
}

func nothingReady(t *testing.T, q *Queue, msg string) {
	t.Helper()
	if l, ok, err := q.Poll("w", 1_000); err != nil || ok {
		t.Fatalf("%s: expected no ready job, got %+v ok=%v err=%v", msg, l, ok, err)
	}
}

func view(t *testing.T, q *Queue, id string) JobView {
	t.Helper()
	v, err := q.View(id)
	if err != nil {
		t.Fatalf("View(%s) failed: %v", id, err)
	}
	return v
}

func ok(t *testing.T, err error, what string) {
	t.Helper()
	if err != nil {
		t.Fatalf("%s failed: %v", what, err)
	}
}

func wantErr(t *testing.T, err, target error, msg string) {
	t.Helper()
	if !errors.Is(err, target) {
		t.Fatalf("%s: want %v, got %v", msg, target, err)
	}
}

// Test: submit then poll hands out a running lease
func TestBasicLease(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	id, err := q.Submit("email", "to=ana", 0, c.NowMillis())
	ok(t, err, "Submit")
	if id != "J1" {
		t.Fatalf("first id = %q, want J1", id)
	}
	l := take(t, q, "w1", 30_000)
	if l.JobID != "J1" || l.Type != "email" || l.Payload != "to=ana" || l.Attempt != 1 || l.LeaseUntil != c.NowMillis()+30_000 {
		t.Fatalf("unexpected lease %+v", l)
	}
	v := view(t, q, id)
	if v.Status != StatusRunning || v.Attempts != 1 {
		t.Fatalf("view after poll: %+v", v)
	}
	if _, again, _ := q.Poll("w2", 30_000); again {
		t.Fatal("a running job is not handed out again")
	}
}

// Test: jobs scheduled in the future are not ready yet
func TestRunAt(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	submit(t, q, "report", 0, c.NowMillis()+5_000)
	nothingReady(t, q, "not ready")
	c.advance(4_999)
	nothingReady(t, q, "1ms early")
	c.advance(1)
	take(t, q, "w", 1_000)
}

// Test: best job: priority, then earliest runAt, then submission order
func TestOrdering(t *testing.T) {
	c := newClock()
	now := c.NowMillis()
	q := defaultQueue(t, c)
	lowOld := submit(t, q, "x", 1, now-500)
	highLate := submit(t, q, "x", 5, now-100)
	highEarly := submit(t, q, "x", 5, now-200)
	highEarlyTwin := submit(t, q, "x", 5, now-200)
	for i, want := range []string{highEarly, highEarlyTwin, highLate, lowOld} {
		if got := take(t, q, "w", 30_000).JobID; got != want {
			t.Fatalf("poll %d: got %s, want %s", i+1, got, want)
		}
	}
}

// Test: complete marks the job succeeded
func TestComplete(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	submit(t, q, "x", 0, c.NowMillis())
	l := take(t, q, "w", 30_000)
	ok(t, q.Complete(l.JobID, l.Token), "Complete")
	if v := view(t, q, l.JobID); v.Status != StatusSucceeded {
		t.Fatalf("status %s, want SUCCEEDED", v.Status)
	}
	nothingReady(t, q, "succeeded job")
	wantErr(t, q.Complete(l.JobID, l.Token), ErrInvalidState, "can't complete twice")
}

// Test: failures retry with exponential backoff, capped
func TestBackoff(t *testing.T) {
	c := newClock()
	q := newQueue(t, c, policy(t, 5, 1_000, 3_000))
	id := submit(t, q, "x", 0, c.NowMillis())
	expected := []int64{1_000, 2_000, 3_000, 3_000} // capped at 3000
	for attempt := 1; attempt <= 4; attempt++ {
		l := take(t, q, "w", 30_000)
		if l.Attempt != attempt {
			t.Fatalf("attempt number: got %d, want %d", l.Attempt, attempt)
		}
		ok(t, q.Fail(id, l.Token, "smtp timeout"), "Fail")
		v := view(t, q, id)
		if v.Status != StatusPending || v.LastError != "smtp timeout" {
			t.Fatalf("after failure: %+v", v)
		}
		if want := c.NowMillis() + expected[attempt-1]; v.NextRunAt != want {
			t.Fatalf("backoff after attempt %d: NextRunAt %d, want %d", attempt, v.NextRunAt, want)
		}
		c.advance(expected[attempt-1] - 1)
		nothingReady(t, q, "1ms before backoff ends")
		c.advance(1)
	}
}

// Test: after MaxAttempts the job is dead-lettered
func TestDeadLetter(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c) // 3 attempts
	id := submit(t, q, "invoice", 0, c.NowMillis())
	submit(t, q, "other", 0, c.NowMillis()+1_000_000)
	for i := 0; i < 3; i++ {
		l := take(t, q, "w", 30_000)
		msg := ""
		if i == 2 {
			msg = "final error"
		}
		ok(t, q.Fail(id, l.Token, msg), "Fail")
		c.advance(10_000)
	}
	v := view(t, q, id)
	if v.Status != StatusDead || v.Attempts != 3 || v.LastError != "final error" {
		t.Fatalf("dead job: %+v", v)
	}
	dead := q.DeadLetters()
	if len(dead) != 1 || dead[0].ID != id {
		t.Fatalf("DeadLetters() = %+v", dead)
	}
	nothingReady(t, q, "dead jobs never run")
}

// Test: fail without a message records "failed"
func TestDefaultError(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	id := submit(t, q, "x", 0, c.NowMillis())
	l := take(t, q, "w", 30_000)
	ok(t, q.Fail(id, l.Token, ""), "Fail")
	if v := view(t, q, id); v.LastError != "failed" {
		t.Fatalf("LastError %q, want \"failed\"", v.LastError)
	}
}

// Test: RetryDead revives a dead job with a fresh attempt budget
func TestRetryDead(t *testing.T) {
	c := newClock()
	q := newQueue(t, c, policy(t, 1, 1_000, 1_000))
	id := submit(t, q, "x", 0, c.NowMillis())
	l := take(t, q, "w", 30_000)
	ok(t, q.Fail(id, l.Token, "boom"), "Fail")
	if v := view(t, q, id); v.Status != StatusDead {
		t.Fatalf("status %s, want DEAD", v.Status)
	}
	ok(t, q.RetryDead(id), "RetryDead")
	if v := view(t, q, id); v.Status != StatusPending || v.Attempts != 0 {
		t.Fatalf("revived job: %+v", v)
	}
	if l := take(t, q, "w", 30_000); l.Attempt != 1 {
		t.Fatalf("ready immediately with attempt 1, got %d", l.Attempt)
	}
	wantErr(t, q.RetryDead(id), ErrInvalidState, "only DEAD jobs")
}

// Test: an expired lease counts as a failed attempt and frees the job
func TestLeaseExpiry(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	id := submit(t, q, "resize", 0, c.NowMillis())
	first := take(t, q, "w1", 5_000)
	c.advance(4_999)
	if _, got, _ := q.Poll("w2", 5_000); got {
		t.Fatal("lease still valid")
	}
	c.advance(1) // lease ends now
	v := view(t, q, id)
	if v.Status != StatusPending || v.LastError != "lease expired" {
		t.Fatalf("after expiry: %+v", v)
	}
	if v.NextRunAt != first.LeaseUntil+1_000 {
		t.Fatalf("backoff counted from when the lease ran out: NextRunAt %d, want %d", v.NextRunAt, first.LeaseUntil+1_000)
	}
	c.advance(1_000)
	second := take(t, q, "w2", 5_000)
	if second.Attempt != 2 {
		t.Fatalf("second attempt number %d", second.Attempt)
	}
	if second.Token == first.Token {
		t.Fatal("every lease gets a new token")
	}
}

// Test: a zombie worker can't complete or fail a job that was re-leased
func TestZombieWorker(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	id := submit(t, q, "x", 0, c.NowMillis())
	zombie := take(t, q, "w1", 1_000)
	c.advance(1_000 + 1_000) // lease expired, backoff elapsed
	fresh := take(t, q, "w2", 60_000)
	wantErr(t, q.Complete(id, zombie.Token), ErrInvalidState, "stale token")
	wantErr(t, q.Fail(id, zombie.Token, "x"), ErrInvalidState, "stale token")
	if v := view(t, q, id); v.Status != StatusRunning {
		t.Fatalf("zombie changed the job: %+v", v)
	}
	ok(t, q.Complete(id, fresh.Token), "Complete with the fresh token")
	if v := view(t, q, id); v.Status != StatusSucceeded {
		t.Fatalf("status %s, want SUCCEEDED", v.Status)
	}
}

// Test: completing after your own lease expired is rejected
func TestLateComplete(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	id := submit(t, q, "x", 0, c.NowMillis())
	l := take(t, q, "w1", 1_000)
	c.advance(1_000)
	wantErr(t, q.Complete(id, l.Token), ErrInvalidState, "late complete")
	if v := view(t, q, id); v.LastError != "lease expired" {
		t.Fatalf("LastError %q, want \"lease expired\"", v.LastError)
	}
}

// Test: wrong token, unknown job, cancel rules
func TestErrors(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	a := submit(t, q, "x", 0, c.NowMillis())
	b := submit(t, q, "x", 0, c.NowMillis()+60_000)
	l := take(t, q, "w", 30_000)
	wantErr(t, q.Complete(a, "made-up"), ErrInvalidState, "made-up token")
	_, err := q.View("J99")
	wantErr(t, err, ErrNotFound, "unknown job")
	wantErr(t, q.Cancel(a), ErrInvalidState, "running job can't be cancelled")
	ok(t, q.Cancel(b), "Cancel")
	if v := view(t, q, b); v.Status != StatusCancelled {
		t.Fatalf("status %s, want CANCELLED", v.Status)
	}
	ok(t, q.Complete(a, l.Token), "Complete")
	c.advance(60_000)
	nothingReady(t, q, "cancelled job never runs")
}

// Test: validation
func TestValidation(t *testing.T) {
	for _, p := range [][3]int64{{0, 1, 1}, {1, 0, 1}, {1, 10, 5}} {
		_, err := NewRetryPolicy(int(p[0]), p[1], p[2])
		wantErr(t, err, ErrInvalidArgument, fmt.Sprintf("NewRetryPolicy%v", p))
	}
	c := newClock()
	q := defaultQueue(t, c)
	_, err := q.Submit(" ", "", 0, 0)
	wantErr(t, err, ErrInvalidArgument, "blank type")
	_, _, err = q.Poll("", 1_000)
	wantErr(t, err, ErrInvalidArgument, "blank worker")
	_, _, err = q.Poll("w", 0)
	wantErr(t, err, ErrInvalidArgument, "leaseMillis 0")
}

// Test: 16 workers: every job is leased once and completed once
func TestConcurrentWorkers(t *testing.T) {
	c := newClock()
	q := defaultQueue(t, c)
	for i := 0; i < 300; i++ {
		if _, err := q.Submit("x", fmt.Sprintf("p%d", i), i%7, c.NowMillis()); err != nil {
			t.Fatal(err)
		}
	}
	var mu sync.Mutex
	leased := map[string]int{}
	var problems atomic.Int32
	var wg sync.WaitGroup
	for w := 0; w < 16; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for {
				l, got, err := q.Poll(fmt.Sprintf("worker-%d", w), 60_000)
				if err != nil {
					problems.Add(1)
					return
				}
				if !got {
					return
				}
				mu.Lock()
				leased[l.JobID]++
				mu.Unlock()
				if q.Complete(l.JobID, l.Token) != nil {
					problems.Add(1)
				}
			}
		}(w)
	}
	wg.Wait()
	if problems.Load() > 0 {
		t.Fatalf("%d poll/complete calls failed", problems.Load())
	}
	if len(leased) != 300 {
		t.Fatalf("leased %d distinct jobs, want 300", len(leased))
	}
	for id, n := range leased {
		if n != 1 {
			t.Fatalf("job %s leased %d times", id, n)
		}
	}
	for i := 1; i <= 300; i++ {
		if v := view(t, q, fmt.Sprintf("J%d", i)); v.Status != StatusSucceeded {
			t.Fatalf("J%d is %s", i, v.Status)
		}
	}
}
