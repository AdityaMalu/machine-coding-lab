A debug task. The headline bug: objects were changed while sitting inside a sorted set.

1. **Duplicate players in the ranking.** A `TreeSet` places an entry by its score when it's inserted. Changing `e.score` afterwards leaves the entry in the wrong place, and the set can no longer find it, so the next `add` inserted a second copy. Entries are now **immutable records**: remove the old entry, then insert a new one.
2. **Scores went down.** Only a strictly higher score replaces a player's best; lower or equal submissions return `false`.
3. **Ties.** Equal scores are ordered by who reached the score first (a sequence number taken when the score is set), then by name.
4. **Ranking style.** Competition ranking: 100, 90, 90, 80 ranks as 1, 2, 2, **4** (the old code gave dense ranks: 1, 2, 2, 3).
5. **Out-of-range views.** `top(n)` with n larger than the board, `around` near either end, and the last page all clip instead of throwing. `around` includes k players on *both* sides (the end index is exclusive, hence `+ k + 1`), and pages are 1-based.
6. **Removed players lingered** in the ranking; `removePlayer` now removes from both structures.
7. **Negative scores** are rejected, every method is `synchronized`, and results are copies (`List.copyOf`), never live views of internal state.
