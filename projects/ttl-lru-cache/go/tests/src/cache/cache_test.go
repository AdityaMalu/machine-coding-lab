package cache

import (
	"errors"
	"math/rand"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type manualClock struct{ now atomic.Int64 }

func newClock() *manualClock {
	c := &manualClock{}
	c.now.Store(1_000)
	return c
}

func (c *manualClock) NowMillis() int64 { return c.now.Load() }
func (c *manualClock) advance(ms int64) { c.now.Add(ms) }

func newCache(t *testing.T, capacity int, ttl int64, c Clock) *Cache[string, string] {
	t.Helper()
	cache, err := New[string, string](capacity, ttl, c)
	if err != nil || cache == nil {
		t.Fatalf("New(%d, %d) failed: %v", capacity, ttl, err)
	}
	return cache
}

// get fails the test unless key holds want.
func wantValue(t *testing.T, c *Cache[string, string], key, want, msg string) {
	t.Helper()
	got, ok := c.Get(key)
	if !ok || got != want {
		t.Fatalf("%s: Get(%q) = %q, %v; want %q, true", msg, key, got, ok, want)
	}
}

func wantMissing(t *testing.T, c *Cache[string, string], key, msg string) {
	t.Helper()
	if got, ok := c.Get(key); ok {
		t.Fatalf("%s: Get(%q) = %q, want missing", msg, key, got)
	}
}

func wantInt(t *testing.T, want, got int64, msg string) {
	t.Helper()
	if got != want {
		t.Fatalf("%s: got %d, want %d", msg, got, want)
	}
}

// Test: put then get; missing keys are reported as missing
func TestBasics(t *testing.T) {
	c := newCache(t, 3, 0, newClock())
	c.Put("a", "1")
	wantValue(t, c, "a", "1", "after put")
	wantMissing(t, c, "nope", "missing key")
	c.Put("a", "2")
	wantValue(t, c, "a", "2", "put replaces")
	wantInt(t, 1, int64(c.Len()), "Len")
}

// Test: evicts the least recently used entry; Get refreshes recency
func TestLRU(t *testing.T) {
	c := newCache(t, 3, 0, newClock())
	c.Put("a", "1")
	c.Put("b", "2")
	c.Put("c", "3")
	c.Get("a")      // order is now b, c, a
	c.Put("d", "4") // evicts b
	wantMissing(t, c, "b", "b was least recently used")
	wantValue(t, c, "a", "1", "a")
	wantValue(t, c, "c", "3", "c")
	wantValue(t, c, "d", "4", "d")
	wantInt(t, 1, c.Stats().Evictions, "evictions")
}

// Test: replacing a key refreshes recency and never evicts
func TestReplaceRefreshes(t *testing.T) {
	c := newCache(t, 2, 0, newClock())
	c.Put("a", "1")
	c.Put("b", "2")
	c.Put("a", "1b") // a becomes most recent, no eviction
	wantInt(t, 0, c.Stats().Evictions, "replace must not evict")
	c.Put("c", "3") // evicts b, not a
	wantMissing(t, c, "b", "b evicted")
	wantValue(t, c, "a", "1b", "a kept")
}

// Test: expiry boundary: live until t+ttl, expired exactly at t+ttl
func TestExpiryBoundary(t *testing.T) {
	clock := newClock()
	c := newCache(t, 10, 500, clock)
	c.Put("k", "v")
	clock.advance(499)
	wantValue(t, c, "k", "v", "1ms before expiry")
	clock.advance(1)
	wantMissing(t, c, "k", "exactly at expiry")
	wantInt(t, 1, c.Stats().Expirations, "expirations")
	wantInt(t, 0, int64(c.Len()), "Len")
}

// Test: Get does not extend the TTL
func TestGetDoesNotExtend(t *testing.T) {
	clock := newClock()
	c := newCache(t, 10, 1_000, clock)
	c.Put("k", "v")
	for i := 0; i < 9; i++ {
		clock.advance(100)
		wantValue(t, c, "k", "v", "still live")
	}
	clock.advance(100)
	wantMissing(t, c, "k", "expires 1000ms after the write, not after the last read")
}

// Test: per-entry TTL; ttl <= 0 never expires
func TestPerEntryTTL(t *testing.T) {
	clock := newClock()
	c := newCache(t, 10, 1_000, clock)
	c.PutTTL("short", "s", 100)
	c.PutTTL("forever", "f", 0)
	c.Put("default", "d")
	clock.advance(100)
	wantMissing(t, c, "short", "short TTL")
	wantValue(t, c, "default", "d", "default TTL")
	clock.advance(1_000_000_000)
	wantValue(t, c, "forever", "f", "ttl 0 never expires")
	wantMissing(t, c, "default", "default TTL long gone")
}

// Test: replacing a key resets its TTL
func TestReplaceResetsTTL(t *testing.T) {
	clock := newClock()
	c := newCache(t, 10, 1_000, clock)
	c.Put("k", "v1")
	clock.advance(900)
	c.Put("k", "v2")
	clock.advance(900)
	wantValue(t, c, "k", "v2", "new TTL started at the replace")
}

// Test: a full cache drops expired entries before evicting live ones
func TestExpiredBeforeLRU(t *testing.T) {
	clock := newClock()
	c := newCache(t, 3, 10_000, clock)
	c.PutTTL("a", "1", 100)
	c.Put("b", "2")
	c.Put("c", "3")
	clock.advance(50)
	wantValue(t, c, "a", "1", "a is now most recently used...")
	clock.advance(150) // ...but expired
	c.Put("d", "4")
	wantValue(t, c, "b", "2", "b must survive: an expired entry was available")
	wantValue(t, c, "c", "3", "c")
	wantValue(t, c, "d", "4", "d")
	s := c.Stats()
	wantInt(t, 0, s.Evictions, "no live entry evicted")
	wantInt(t, 1, s.Expirations, "expirations")
}

// Test: Len counts only live entries; Remove reports live removals
func TestLenAndRemove(t *testing.T) {
	clock := newClock()
	c := newCache(t, 10, 100, clock)
	c.Put("a", "1")
	c.PutTTL("b", "2", 0)
	wantInt(t, 2, int64(c.Len()), "Len")
	clock.advance(100)
	wantInt(t, 1, int64(c.Len()), "a expired")
	if !c.Remove("b") {
		t.Fatal("Remove(b) should report a live removal")
	}
	if c.Remove("b") {
		t.Fatal("b is already gone")
	}
	if c.Remove("zzz") {
		t.Fatal("zzz never existed")
	}
	wantInt(t, 0, int64(c.Len()), "empty")
}

// Test: hits and misses are counted by Get and GetOrLoad
func TestStats(t *testing.T) {
	c := newCache(t, 10, 0, newClock())
	c.Get("x") // miss
	c.Put("x", "1")
	c.Get("x")                                                              // hit
	c.GetOrLoad("y", func(string) (string, error) { return "loaded", nil }) // miss
	c.GetOrLoad("y", func(string) (string, error) { return "never", nil })  // hit
	s := c.Stats()
	wantInt(t, 2, s.Hits, "hits")
	wantInt(t, 2, s.Misses, "misses")
	wantValue(t, c, "y", "loaded", "loaded value cached")
}

// Test: a loader error caches nothing; a zero value is a real value and is cached
func TestLoadErrorVsZeroValue(t *testing.T) {
	c := newCache(t, 10, 0, newClock())
	var calls atomic.Int32
	failing := func(string) (string, error) {
		calls.Add(1)
		return "", errors.New("backend down")
	}
	c.GetOrLoad("k", failing)
	c.GetOrLoad("k", failing)
	if calls.Load() != 2 {
		t.Fatalf("a failed load must not be cached: loader ran %d times, want 2", calls.Load())
	}
	wantInt(t, 0, int64(c.Len()), "nothing cached after errors")
	if v, err := c.GetOrLoad("empty", func(string) (string, error) { return "", nil }); err != nil || v != "" {
		t.Fatalf("GetOrLoad(empty) = %q, %v", v, err)
	}
	wantValue(t, c, "empty", "", "an empty string is a value")
}

// Test: a failing loader's error reaches the caller and the next call retries
func TestLoaderFailure(t *testing.T) {
	c := newCache(t, 10, 0, newClock())
	down := errors.New("backend down")
	if _, err := c.GetOrLoad("k", func(string) (string, error) { return "", down }); !errors.Is(err, down) {
		t.Fatalf("the loader's own error must reach the caller, got %v", err)
	}
	v, err := c.GetOrLoad("k", func(string) (string, error) { return "ok", nil })
	if err != nil || v != "ok" {
		t.Fatalf("retry after failure: got %q, %v", v, err)
	}
}

// Test: single flight: 16 concurrent misses run the loader once
func TestSingleFlight(t *testing.T) {
	c := newCache(t, 10, 0, newClock())
	var loads atomic.Int32
	var wg sync.WaitGroup
	results := make([]string, 16)
	start := make(chan struct{})
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			v, err := c.GetOrLoad("hot", func(string) (string, error) {
				loads.Add(1)
				time.Sleep(150 * time.Millisecond)
				return "price-42", nil
			})
			if err != nil {
				v = "error: " + err.Error()
			}
			results[i] = v
		}(i)
	}
	close(start)
	wg.Wait()
	if loads.Load() != 1 {
		t.Fatalf("the backend must be called exactly once, was called %d times", loads.Load())
	}
	for i, v := range results {
		if v != "price-42" {
			t.Fatalf("caller %d got %q, want price-42", i, v)
		}
	}
}

// Test: single flight: waiters receive the loader's error
func TestSingleFlightFailure(t *testing.T) {
	c := newCache(t, 10, 0, newClock())
	boom := errors.New("boom")
	var loads, failures atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			_, err := c.GetOrLoad("bad", func(string) (string, error) {
				loads.Add(1)
				time.Sleep(150 * time.Millisecond)
				return "", boom
			})
			if errors.Is(err, boom) {
				failures.Add(1)
			}
		}()
	}
	close(start)
	wg.Wait()
	if loads.Load() != 1 {
		t.Fatalf("one load for all callers, got %d", loads.Load())
	}
	if failures.Load() != 8 {
		t.Fatalf("every caller sees the failure, got %d of 8", failures.Load())
	}
}

// Test: a slow load for one key does not block other keys
func TestNoGlobalLockDuringLoad(t *testing.T) {
	c := newCache(t, 10, 0, newClock())
	c.Put("fast", "1")
	done := make(chan struct{})
	go func() {
		defer close(done)
		c.GetOrLoad("slow", func(string) (string, error) {
			time.Sleep(time.Second)
			return "2", nil
		})
	}()
	time.Sleep(100 * time.Millisecond)
	start := time.Now()
	wantValue(t, c, "fast", "1", "other key")
	if ms := time.Since(start).Milliseconds(); ms >= 500 {
		t.Fatalf("Get on another key waited %dms for the slow loader", ms)
	}
	<-done
}

// Test: validation
func TestValidation(t *testing.T) {
	if _, err := New[string, string](0, 10, newClock()); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("capacity 0: want ErrInvalidArgument, got %v", err)
	}
	if _, err := New[string, string](1, 10, nil); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("nil clock: want ErrInvalidArgument, got %v", err)
	}
	c := newCache(t, 2, 10, newClock())
	if _, err := c.GetOrLoad("k", nil); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("nil loader: want ErrInvalidArgument, got %v", err)
	}
}

// Test: never exceeds capacity under concurrent use
func TestConcurrentCapacity(t *testing.T) {
	c, err := New[int, int](20, 0, newClock())
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	var tooBig atomic.Int32
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(seed int64) {
			defer wg.Done()
			rnd := rand.New(rand.NewSource(seed))
			for k := 0; k < 3_000; k++ {
				key := rnd.Intn(60)
				if rnd.Intn(2) == 0 {
					c.Put(key, k)
				} else {
					c.Get(key)
				}
				if k%100 == 0 && c.Len() > 20 {
					tooBig.Add(1)
				}
			}
		}(int64(g))
	}
	wg.Wait()
	if tooBig.Load() > 0 || c.Len() > 20 {
		t.Fatalf("size exceeded capacity (%d times, final %d)", tooBig.Load(), c.Len())
	}
	if c.Len() < 1 {
		t.Fatalf("after thousands of puts the cache can't be empty, Len() = %d", c.Len())
	}
}
