# Leaderboard Service (Python)

**Scenario.** A game's season leaderboard is live and the support queue is filling up:
- *"I scored lower in a later match and my best score went **down**."*
- *"Two of us tied for 2nd, but the next player is shown as 3rd. Shouldn't they be 4th?"*
- *"I hit 900 first, yet the other player with 900 is listed above me."*
- *"After I improved my score I appear **twice** on the board."*
- *"I deleted my account but I'm still on the leaderboard."*
- *"The 'players around me' view is empty when I'm near the top"*, *"asking for the top 50 crashes"*, and
  *"page 1 skips the top players"*.
- Under load, some players' best scores are lost.

The service in your workspace is production code. Fix it.

## Contract (package `leaderboard`)
`RankedEntry(rank, player, score)` and `Page(page_number, page_size, total_players, entries)` are given.

| Method | Behaviour |
|---|---|
| `submit_score(player, score) -> bool` | Keeps each player's **best** score. Returns `True` if this score became their new best (strictly higher, or first score). Blank/`None` player or negative score → `ValueError`. |
| `top(n) -> list[RankedEntry]` | The first `n` entries (all of them if fewer). `n < 0` → `ValueError`. |
| `rank_of(player) -> int` | Their rank. Unknown → `KeyError`. |
| `around(player, k) -> list[RankedEntry]` | Up to `k` entries above and `k` below the player, plus the player, clipped at both ends. Unknown → `KeyError`; `k < 0` → `ValueError`. |
| `page(page_number, page_size) -> Page` | `page_number` starts at **1**. `page_size` 1–100. Out-of-range pages are empty. |
| `remove_player(player) -> bool` | Removes them everywhere. `False` if unknown. |
| `size() -> int` | Number of players. |

### Ordering and ranks
- Order by score (highest first). Equal scores: whoever **reached that score first** comes first. Improving your score
  counts as reaching the new score at that moment. Then by player name, as a last resort.
- **Competition ranking ("1224")**: players with equal scores share a rank, and the next rank skips accordingly.
  Scores 100, 90, 90, 80 → ranks 1, 2, 2, 4.

Thread-safe: many score submissions arrive concurrently.

## Debugging hints
- The ranking is a list kept sorted with `bisect.insort`. What happens to it when you change the score of an entry
  that is **already in the list**?
- Read each slice in `around` and `page` with a small example on paper. What does a **negative** start index do in
  Python?
