from .stats import CacheStats


class TtlLruCache:
    def __init__(self, capacity, default_ttl_millis, time):
        # TODO validate and set up storage (hint: collections.OrderedDict keeps an order you can move keys in)
        pass

    def get(self, key):
        raise NotImplementedError("TODO")

    def put(self, key, value, ttl_millis=None):
        """ttl_millis=None means the default TTL."""
        raise NotImplementedError("TODO")

    def remove(self, key):
        raise NotImplementedError("TODO")

    def size(self):
        raise NotImplementedError("TODO")

    def compute_if_absent(self, key, loader):
        # TODO single flight: concurrent callers for the same missing key share one load
        raise NotImplementedError("TODO")

    def stats(self):
        raise NotImplementedError("TODO")
