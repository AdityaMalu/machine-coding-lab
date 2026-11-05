A debug task: the scoring rules disagreed with the spec at the boundaries and in one piece of logic, and one bad row broke the whole batch.

1. **Late-return tiers.** They were cumulative and inclusive: 30 days counted as late, and 61 days scored 25 + 40. The tiers are now exclusive with a strict `>`: over 60 days scores +40, over 30 scores +25, and 30 days isn't late.
2. **The $500 tier.** "$500 or more" is `>= 50_000` cents, not `>`.
3. **Opened electronics.** The +10 is for items that are opened **and** electronics, not either one.
4. **Risk levels.** HIGH starts *at* 60 (`>=`), and the score is capped at 100.
5. **Validation.** A negative return count or a missing category is rejected instead of producing a meaningless score.
6. **The batch.** One invalid request threw and lost every result. Each row is now assessed on its own: a bad row becomes an error entry and the rest are still scored.
7. **The reasons list** is returned with `List.copyOf`, so callers can't modify a result.
