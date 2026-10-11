# Leaderboard Service (C++)

**Scenario.** A game's season leaderboard is live and the support queue is filling up:
- *"I scored lower in a later match and my best score went **down**."*
- *"Two of us tied for 2nd, but the next player is shown as 3rd. Shouldn't they be 4th?"*
- *"I hit 900 first, yet the other player with 900 is listed above me."*
- *"After I improved my score I appear **twice** on the board."*
- *"I deleted my account but I'm still on the leaderboard."*
- *"The 'players around me' view errors when I'm near the top"*, *"asking for the top 50 errors"*, and
  *"page 1 skips the top players"*.
- Under load, some players' best scores are lost, even though the code takes a lock.

The service in your workspace is production code. Fix it.

## Contract (header `leaderboard.hpp`)
`RankedEntry{rank, player, score}` and `Page{pageNumber, pageSize, totalPlayers, entries}` are given
(`leaderboard_models.hpp`).

| Method | Behaviour |
|---|---|
| `bool submitScore(player, score)` | Keeps each player's **best** score. `true` if this score became their new best (strictly higher, or first score). Blank player or negative score → `std::invalid_argument`. |
| `std::vector<RankedEntry> top(int n)` | The first `n` entries (all of them if fewer). `n < 0` → `std::invalid_argument`. |
| `int rankOf(player)` | Their rank. Unknown → `std::out_of_range`. |
| `std::vector<RankedEntry> around(player, int k)` | Up to `k` entries above and `k` below the player, plus the player, clipped at both ends. Unknown → `std::out_of_range`; `k < 0` → `std::invalid_argument`. |
| `Page page(int pageNumber, int pageSize)` | `pageNumber` starts at **1**. `pageSize` 1–100. Out-of-range pages are empty. |
| `bool removePlayer(player)` | Removes them everywhere. `false` if unknown. |
| `int size()` | Number of players. |

### Ordering and ranks
- Order by score (highest first). Equal scores: whoever **reached that score first** comes first. Improving your score
  counts as reaching the new score at that moment. Then by player name, as a last resort.
- **Competition ranking ("1224")**: players with equal scores share a rank, and the next rank skips accordingly.
  Scores 100, 90, 90, 80 → ranks 1, 2, 2, 4.

Thread-safe: many score submissions arrive concurrently.

## Debugging hints
- The ranking is a sorted `std::vector<std::shared_ptr<Entry>>`. What happens to it when you change the score through
  a pointer to an entry that is **already in the vector**? (With a `std::set` it would be undefined behaviour.)
- Read each index calculation in `top`, `around` and `page` with a small example on paper.
- Two short critical sections are not one atomic operation: what can another thread do between them?
