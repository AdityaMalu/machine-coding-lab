import threading
from collections import OrderedDict

from .stats import CacheStats

_NEVER = float("inf")


class _Load:
    """One load in progress: the first caller runs it, everyone else waits on the event."""

    def __init__(self):
        self.done = threading.Event()
        self.value = None
        self.error = None


class TtlLruCache:
    def __init__(self, capacity, default_ttl_millis, time):
        if not isinstance(capacity, int) or capacity < 1:
            raise ValueError("capacity must be >= 1")
        if time is None:
            raise ValueError("time required")
        self._capacity = capacity
        self._default_ttl = default_ttl_millis
        self._time = time
        self._entries = OrderedDict()  # key -> (value, expires_at); first key is the least recently used
        self._loads = {}
        self._lock = threading.Lock()
        self._hits = self._misses = self._evictions = self._expirations = 0

    def get(self, key):
        _require(key, "key")
        with self._lock:
            entry = self._live(key, self._time.now_millis())
            if entry is None:
                self._misses += 1
                return None
            self._hits += 1
            return entry[0]

    def put(self, key, value, ttl_millis=None):
        _require(key, "key")
        _require(value, "value")
        with self._lock:
            self._put(key, value, self._default_ttl if ttl_millis is None else ttl_millis)

    def remove(self, key):
        _require(key, "key")
        with self._lock:
            entry = self._entries.pop(key, None)
            if entry is None:
                return False
            if self._time.now_millis() >= entry[1]:
                self._expirations += 1
                return False
            return True

    def size(self):
        with self._lock:
            self._purge_expired(self._time.now_millis())
            return len(self._entries)

    def compute_if_absent(self, key, loader):
        _require(key, "key")
        if loader is None:
            raise ValueError("loader required")
        with self._lock:
            entry = self._live(key, self._time.now_millis())
            if entry is not None:
                self._hits += 1
                return entry[0]
            self._misses += 1
            load = self._loads.get(key)
            mine = load is None
            if mine:
                load = self._loads[key] = _Load()
        if not mine:
            load.done.wait()
            if load.error is not None:
                raise load.error
            return load.value

        # we are the loader; run it without holding the cache lock
        try:
            value = loader(key)
        except BaseException as e:
            with self._lock:
                del self._loads[key]
            load.error = e
            load.done.set()
            raise
        with self._lock:
            del self._loads[key]
            if value is not None:
                self._put(key, value, self._default_ttl)
        load.value = value
        load.done.set()
        return value

    def stats(self):
        with self._lock:
            return CacheStats(self._hits, self._misses, self._evictions, self._expirations)

    # ---------------------------------------------------------------- internals (call with the lock held)

    def _put(self, key, value, ttl):
        now = self._time.now_millis()
        old = self._entries.pop(key, None)
        if old is not None and now >= old[1]:
            self._expirations += 1
        if old is None:
            self._make_room(now)
        self._entries[key] = (value, _NEVER if ttl is None or ttl <= 0 else now + ttl)

    def _live(self, key, now):
        entry = self._entries.get(key)
        if entry is None:
            return None
        if now >= entry[1]:
            del self._entries[key]
            self._expirations += 1
            return None
        self._entries.move_to_end(key)
        return entry

    def _make_room(self, now):
        if len(self._entries) < self._capacity:
            return
        self._purge_expired(now)
        while len(self._entries) >= self._capacity:
            self._entries.popitem(last=False)
            self._evictions += 1

    def _purge_expired(self, now):
        for k in [k for k, (_, expires) in self._entries.items() if now >= expires]:
            del self._entries[k]
            self._expirations += 1


def _require(value, name):
    if value is None:
        raise ValueError(name + " required")
