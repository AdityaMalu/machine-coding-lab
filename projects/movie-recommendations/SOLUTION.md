A debug task: the ranking formula, the candidate filter and an unsafe cache all disagreed with the spec.

1. **Already-rated movies were recommended.** Candidates now exclude movies the user has **rated or watched**, not only watched ones.
2. **Wrong score.** A movie's score is the average of the user's genre preferences over *the movie's own genres* (`sum / m.genres().size()`). The old code divided by the number of genres the user had rated, which unfairly favoured movies with many genres.
3. **Tie-break direction.** Equal scores are broken by the movie's global average rating, **highest first** (the old code sorted it ascending), then title, then id, so the order is always fully determined.
4. **Stale and shared results.** The per-user cache never invalidated, and it returned the same mutable list to every caller (`subList` is a live view). Results are computed fresh and returned as `List.copyOf`.
5. **`k` larger than the candidates** threw; it's now clamped.
6. **Inputs changed from outside.** The constructor stored the caller's lists and maps, so later edits by the caller changed the recommender. Everything is copied defensively, and global averages are computed once up front.
7. **Ratings for unknown movies** (not in the catalog) caused a `NullPointerException`; they're skipped.
