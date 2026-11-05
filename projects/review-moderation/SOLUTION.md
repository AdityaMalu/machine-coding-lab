A debug task: concurrency, word matching and listener failures.

1. **Two moderators could both decide the same review.** `decide` runs inside `ConcurrentHashMap.compute`, which is atomic per key: it checks the review is still PENDING and saves the decision in one step, so exactly one racing moderator wins and the other gets `IllegalStateException`.
2. **Listeners saw unsaved decisions.** They were notified *before* the decision was stored. Now they're notified after it's saved, outside the map lock.
3. **A failing listener broke moderation.** Each listener call is wrapped; failures are counted in `failedNotifications()` and the other listeners still run.
4. **Banned words matched inside other words** ("class" contains "ass"), and missed upper case. Text is split into lower-cased whole words, and the banned list is lower-cased too.
5. **Whitespace-only reviews passed** the length check. Text is trimmed first, then must be 1..1000 characters.
6. **Wrong order.** Ids sorted as text put S10 before S2; they're sorted by number.
7. **Unsafe shared state.** `HashMap`, `ArrayList` and `int` counters were used from many threads. They're now `ConcurrentHashMap`, `CopyOnWriteArrayList` (listeners are added rarely and read on every decision) and atomic counters.
