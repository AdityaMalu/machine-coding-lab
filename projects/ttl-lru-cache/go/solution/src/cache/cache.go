package cache

import (
	"container/list"
	"fmt"
	"math"
	"sync"
)

type entry[K comparable, V any] struct {
	key       K
	value     V
	expiresAt int64
}

// call is one load in progress: the first caller runs it, the others wait on done.
type call[V any] struct {
	done  chan struct{}
	value V
	err   error
}

// Cache is a size-bounded LRU cache whose entries expire after a TTL.
type Cache[K comparable, V any] struct {
	mu         sync.Mutex
	capacity   int
	defaultTTL int64
	clock      Clock
	order      *list.List // front = most recently used
	items      map[K]*list.Element
	loads      map[K]*call[V]
	stats      Stats
}

// New creates a cache. defaultTTL <= 0 means entries never expire by default.
func New[K comparable, V any](capacity int, defaultTTL int64, clock Clock) (*Cache[K, V], error) {
	if capacity < 1 {
		return nil, fmt.Errorf("capacity must be >= 1: %w", ErrInvalidArgument)
	}
	if clock == nil {
		return nil, fmt.Errorf("clock required: %w", ErrInvalidArgument)
	}
	return &Cache[K, V]{capacity: capacity, defaultTTL: defaultTTL, clock: clock, order: list.New(),
		items: map[K]*list.Element{}, loads: map[K]*call[V]{}}, nil
}

// Get returns the live value for key, and false if it is absent or expired.
func (c *Cache[K, V]) Get(key K) (V, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e := c.live(key, c.clock.NowMillis()); e != nil {
		c.stats.Hits++
		return e.value, true
	}
	c.stats.Misses++
	var zero V
	return zero, false
}

// Put inserts or replaces key with the default TTL.
func (c *Cache[K, V]) Put(key K, value V) {
	c.PutTTL(key, value, c.defaultTTL)
}

// PutTTL inserts or replaces key with its own TTL; ttl <= 0 never expires.
func (c *Cache[K, V]) PutTTL(key K, value V, ttl int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.put(key, value, ttl)
}

// Remove reports whether a live entry was removed.
func (c *Cache[K, V]) Remove(key K) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[key]
	if !ok {
		return false
	}
	c.unlink(el)
	if c.clock.NowMillis() >= el.Value.(*entry[K, V]).expiresAt {
		c.stats.Expirations++
		return false
	}
	return true
}

// Len is the number of live entries.
func (c *Cache[K, V]) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.purgeExpired(c.clock.NowMillis())
	return len(c.items)
}

// GetOrLoad returns the cached value, or loads, caches and returns it.
// Concurrent callers for the same missing key share one load (single flight).
func (c *Cache[K, V]) GetOrLoad(key K, loader func(K) (V, error)) (V, error) {
	var zero V
	if loader == nil {
		return zero, fmt.Errorf("loader required: %w", ErrInvalidArgument)
	}
	c.mu.Lock()
	if e := c.live(key, c.clock.NowMillis()); e != nil {
		c.stats.Hits++
		c.mu.Unlock()
		return e.value, nil
	}
	c.stats.Misses++
	if inflight, ok := c.loads[key]; ok {
		c.mu.Unlock()
		<-inflight.done
		return inflight.value, inflight.err
	}
	mine := &call[V]{done: make(chan struct{})}
	c.loads[key] = mine
	c.mu.Unlock()

	// we are the loader; run it without holding the cache lock
	mine.value, mine.err = loader(key)
	c.mu.Lock()
	delete(c.loads, key)
	if mine.err == nil {
		c.put(key, mine.value, c.defaultTTL)
	}
	c.mu.Unlock()
	close(mine.done)
	return mine.value, mine.err
}

// Stats returns the counters since creation.
func (c *Cache[K, V]) Stats() Stats {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.stats
}

// ---------------------------------------------------------------- internals (call with mu held)

func (c *Cache[K, V]) put(key K, value V, ttl int64) {
	now := c.clock.NowMillis()
	if el, ok := c.items[key]; ok {
		if now >= el.Value.(*entry[K, V]).expiresAt {
			c.stats.Expirations++
		}
		c.unlink(el)
	} else {
		c.makeRoom(now)
	}
	expires := int64(math.MaxInt64)
	if ttl > 0 && now <= math.MaxInt64-ttl {
		expires = now + ttl
	}
	c.items[key] = c.order.PushFront(&entry[K, V]{key: key, value: value, expiresAt: expires})
}

// live returns the entry for key and marks it most recently used, removing it if it has expired.
func (c *Cache[K, V]) live(key K, now int64) *entry[K, V] {
	el, ok := c.items[key]
	if !ok {
		return nil
	}
	e := el.Value.(*entry[K, V])
	if now >= e.expiresAt {
		c.unlink(el)
		c.stats.Expirations++
		return nil
	}
	c.order.MoveToFront(el)
	return e
}

func (c *Cache[K, V]) makeRoom(now int64) {
	if len(c.items) < c.capacity {
		return
	}
	c.purgeExpired(now)
	for len(c.items) >= c.capacity {
		c.unlink(c.order.Back())
		c.stats.Evictions++
	}
}

func (c *Cache[K, V]) purgeExpired(now int64) {
	for el := c.order.Front(); el != nil; {
		next := el.Next()
		if now >= el.Value.(*entry[K, V]).expiresAt {
			c.unlink(el)
			c.stats.Expirations++
		}
		el = next
	}
}

func (c *Cache[K, V]) unlink(el *list.Element) {
	c.order.Remove(el)
	delete(c.items, el.Value.(*entry[K, V]).key)
}
