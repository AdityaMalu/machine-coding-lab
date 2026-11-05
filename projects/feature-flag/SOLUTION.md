The key idea is **deterministic bucketing**: the same user must always get the same answer for a flag, with no stored per-user state.

- **`Bucketing.bucket`** is FNV-1a over the key's UTF-8 bytes, then an unsigned `% buckets`. It's the same in every run and every language, spreads keys evenly, and `toUnsignedLong` avoids negative buckets.
- **Evaluation order** follows the spec: an unknown flag or missing user is an error; a disabled flag is `"off"`; then the first targeting **rule** that matches a user attribute wins; then the **rollout** check (`bucket(flag:user) < rolloutPercent` means the user is in); then a weighted **variant** pick.
- **Separate hash inputs** (`flag:user` for rollout, `flag:variant:user` for the variant) keep the two decisions independent. With the same input, everyone in a 10% rollout would also land in the first 10% of variants.
- **Weighted variants**: walk the cumulative weights until the bucket falls under the running total (weights add up to 100).
- **Validation at `define` time**: key, rollout 0..100, non-negative weights summing to 100, well-formed rules. Bad config fails when it's defined, not later when a user hits it.
- **Thread safety**: the flag is copied into an immutable snapshot (`List.copyOf`) stored in a `ConcurrentHashMap`, so readers never see a half-built flag and redefining one is a single atomic replace.
