package cache

import "errors"

var errTODO = errors.New("TODO")

// Cache is a size-bounded LRU cache whose entries expire after a TTL.
type Cache[K comparable, V any] struct {
	// TODO storage (hint: container/list for recency order plus a map from key to list element)
}

// New creates a cache. defaultTTL <= 0 means entries never expire by default.
func New[K comparable, V any](capacity int, defaultTTL int64, clock Clock) (*Cache[K, V], error) {
	// TODO validate
	return &Cache[K, V]{}, nil
}

// Get returns the live value for key, and false if it is absent or expired.
func (c *Cache[K, V]) Get(key K) (V, bool) {
	var zero V
	return zero, false // TODO
}

// Put inserts or replaces key with the default TTL.
func (c *Cache[K, V]) Put(key K, value V) {
	// TODO
}

// PutTTL inserts or replaces key with its own TTL; ttl <= 0 never expires.
func (c *Cache[K, V]) PutTTL(key K, value V, ttl int64) {
	// TODO
}

// Remove reports whether a live entry was removed.
func (c *Cache[K, V]) Remove(key K) bool {
	return false // TODO
}

// Len is the number of live entries.
func (c *Cache[K, V]) Len() int {
	return -1 // TODO
}

// GetOrLoad returns the cached value, or loads, caches and returns it.
// Concurrent callers for the same missing key share one load (single flight).
func (c *Cache[K, V]) GetOrLoad(key K, loader func(K) (V, error)) (V, error) {
	var zero V
	return zero, errTODO
}

// Stats returns the counters since creation.
func (c *Cache[K, V]) Stats() Stats {
	return Stats{} // TODO
}
