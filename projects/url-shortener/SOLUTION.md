A build task. The design: a counter turned into Base62 codes, one map from code to link, and one lock.

- **Base62.** `encode` repeatedly takes `n % 62` (digits come out backwards, hence the reverse); `decode` is the inverse, using `multiplyExact`/`addExact` so a too-long code throws instead of silently overflowing.
- **Codes from a counter, not random.** A counter never collides, so there's no retry loop for generated codes. Starting at `62^5` makes every code at least 6 characters long. The counter skips any value a custom alias already took.
- **Dedup.** Shortening the same URL again returns the existing generated code while it's still valid; aliases are never reused for dedup, since they're the owner's choice.
- **Aliases** must match `[A-Za-z0-9_-]{3,32}` and be free, or the call fails.
- **Expiry boundaries.** A link is dead when `now >= expiresAt`, so at exactly the TTL it's already gone. A TTL of 0 or less means "never"; `now + ttl` overflow is treated as never too.
- **Stats.** `resolve` counts hits and the last access time; expired or unknown codes resolve to `Optional.empty()` and don't count.
- **URL validation.** http(s) only, a host must follow the scheme, no whitespace, at most 2048 characters.
- **Thread safety.** Every public method is `synchronized`: "is the alias free? then take it" and the counter must each be one atomic step.
