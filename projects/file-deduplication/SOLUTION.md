A build task. The design is a funnel: each stage is cheaper than the next and throws away most candidates.

1. **Group by size.** Files of different sizes can't be identical, and the size costs nothing to read. Only same-size groups with 2 or more files go on, so most files are never opened. Only regular files count (`NOFOLLOW_LINKS`: symlinks are skipped, so a link loop can't trap the walk).
2. **Hash the candidates, in parallel.** Hashing reads whole files, so it runs on a fixed pool of `threads` workers. Results and failures go into `ConcurrentHashMap`s. A file that can't be read is recorded in `failures` and the scan continues. `Sha256Hasher` streams the file through `MessageDigest` in 64 KB chunks, so a huge file never has to fit in memory.
3. **Confirm with an exact byte comparison.** Equal hashes are very strong evidence but not proof (the tests use a hasher that returns the same hash for everything). Within each (size, hash) bucket, `Files.mismatch(a, b) == -1` checks the bytes for real, splitting a bucket into true groups.
4. **Report.** Groups and the files in them are sorted, so the output is deterministic. Reclaimable space is `size × (copies − 1)` per group: you keep one copy.

The pool is always shut down in `finally`, so an error can't leak threads.
