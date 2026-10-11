"""Given."""

from dataclasses import dataclass


@dataclass(frozen=True)
class CacheStats:
    hits: int
    misses: int
    evictions: int
    expirations: int
