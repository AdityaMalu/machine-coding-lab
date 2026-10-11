package cache

// Given.

import "errors"

// Clock is injected so tests control time.
type Clock interface{ NowMillis() int64 }

// Stats are counters since the cache was created.
type Stats struct {
	Hits, Misses, Evictions, Expirations int64
}

// ErrInvalidArgument is returned (wrapped) for bad arguments.
var ErrInvalidArgument = errors.New("invalid argument")
