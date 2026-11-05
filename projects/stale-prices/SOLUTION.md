Five bugs across caching and paging.

1. **Wrong currency.** The cache key was only `#sku`, so the first currency requested was served to everyone. The key is now `#sku + ':' + #currency`.
2. **Old prices after an update.** `@CacheEvict` on a *private* method called from inside the same class never runs: Spring's caching proxy only sees calls coming from outside. The eviction moved onto the public `updatePrice`, and with `allEntries = true`, since every currency's view of the product is stale.
3. **Cache stampede.** When an uncached product gets a burst of requests, each one went to the slow store. `sync = true` lets one caller load the value while the others wait for it.
4. **Bulk discount off by one.** The rule is "10 or more", so `>=`, not `>`.
5. **Paging loses products.** `subList(from, to)` already excludes `to`, so `from + size - 1` dropped the last item of every page; `from + size` is right. Category matching is also case-insensitive now, as the spec says.
