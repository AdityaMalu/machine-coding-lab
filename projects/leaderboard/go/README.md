# Leaderboard Service (Go)

**Scenario.** A game's season leaderboard is live and the support queue is filling up:
- *"I scored lower in a later match and my best score went **down**."*
- *"Two of us tied for 2nd, but the next player is shown as 3rd. Shouldn't they be 4th?"*
- *"I hit 900 first, yet the other player with 900 is listed above me."*
- *"After I improved my score I appear **twice** on the board."*
- *"I deleted my account but I'm still on the leaderboard."*
- *"The 'players around me' view crashes when I'm near the top"*, and *"page 1 skips the top players"*.
- Under load the service crashes (`fatal error: concurrent map writes`) or loses best scores.

The service in your workspace is production code. Fix it.

## Contract (package `leaderboard`)
`RankedEntry{Rank, Player, Score}`, `Page{PageNumber, PageSize, TotalPlayers, Entries}`, `ErrInvalidArgument` and
`ErrNotFound` are given. `New()` creates a leaderboard.

| Method | Behaviour |
|---|---|
| `SubmitScore(player string, score int64) (bool, error)` | Keeps each player's **best** score. `true` if this score became their new best (strictly higher, or first score). Blank player or negative score → `ErrInvalidArgument`. |
| `Top(n int) ([]RankedEntry, error)` | The first `n` entries (all of them if fewer). `n < 0` → `ErrInvalidArgument`. |
| `RankOf(player string) (int, error)` | Their rank. Unknown → `ErrNotFound`. |
| `Around(player string, k int) ([]RankedEntry, error)` | Up to `k` entries above and `k` below the player, plus the player, clipped at both ends. Unknown → `ErrNotFound`; `k < 0` → `ErrInvalidArgument`. |
| `Page(pageNumber, pageSize int) (Page, error)` | `pageNumber` starts at **1**. `pageSize` 1–100. Out-of-range pages are empty. |
| `RemovePlayer(player string) bool` | Removes them everywhere. `false` if unknown. |
| `Size() int` | Number of players. |

Errors are wrapped; tests check them with `errors.Is`.

### Ordering and ranks
- Order by score (highest first). Equal scores: whoever **reached that score first** comes first. Improving your score
  counts as reaching the new score at that moment. Then by player name, as a last resort.
- **Competition ranking ("1224")**: players with equal scores share a rank, and the next rank skips accordingly.
  Scores 100, 90, 90, 80 → ranks 1, 2, 2, 4.

Goroutine-safe: many score submissions arrive concurrently.

## Debugging hints
- The ranking is a slice of `*entry` kept sorted. What happens to it when you change the score through a pointer to
  an entry that is **already in the slice**?
- Read each slice expression in `Top`, `Around` and `Page` with a small example on paper. Which ones can panic with
  "slice bounds out of range"?
