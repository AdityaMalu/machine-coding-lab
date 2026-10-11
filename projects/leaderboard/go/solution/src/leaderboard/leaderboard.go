package leaderboard

import (
	"fmt"
	"sort"
	"strings"
	"sync"
)

// Fixes vs. the production version:
//   - Entries are never changed in place. Changing the score of an entry that is already in the sorted slice left
//     it in the wrong position and inserted it a second time (the duplicates); now the old entry is removed first.
//   - Only a strictly higher score replaces the best, and it records a new "reached at" sequence.
//   - Ties are ordered by who reached the score first (seq), then name.
//   - Competition ranking (1,2,2,4) instead of dense ranking (1,2,2,3).
//   - Top/Around/Page clip their ranges (the old slicing panicked); page numbers are 1-based; Around includes k below.
//   - RemovePlayer removes from both structures; negative scores rejected; one mutex around everything.
type entry struct {
	player string
	score  int64
	seq    int64 // when this score was reached; earlier wins ties
}

type Leaderboard struct {
	mu       sync.Mutex
	byPlayer map[string]entry
	ranking  []entry // sorted by less
	nextSeq  int64
}

func New() *Leaderboard {
	return &Leaderboard{byPlayer: map[string]entry{}, nextSeq: 1}
}

func less(a, b entry) bool {
	if a.score != b.score {
		return a.score > b.score
	}
	if a.seq != b.seq {
		return a.seq < b.seq
	}
	return a.player < b.player
}

func (b *Leaderboard) SubmitScore(player string, score int64) (bool, error) {
	if strings.TrimSpace(player) == "" {
		return false, fmt.Errorf("player required: %w", ErrInvalidArgument)
	}
	if score < 0 {
		return false, fmt.Errorf("score must be >= 0: %w", ErrInvalidArgument)
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	old, ok := b.byPlayer[player]
	if ok && score <= old.score {
		return false, nil
	}
	if ok {
		b.remove(old)
	}
	e := entry{player: player, score: score, seq: b.nextSeq}
	b.nextSeq++
	b.byPlayer[player] = e
	i := sort.Search(len(b.ranking), func(i int) bool { return less(e, b.ranking[i]) })
	b.ranking = append(b.ranking, entry{})
	copy(b.ranking[i+1:], b.ranking[i:])
	b.ranking[i] = e
	return true, nil
}

func (b *Leaderboard) Top(n int) ([]RankedEntry, error) {
	if n < 0 {
		return nil, fmt.Errorf("n must be >= 0: %w", ErrInvalidArgument)
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	all := b.rankAll()
	return all[:min(n, len(all))], nil
}

func (b *Leaderboard) RankOf(player string) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	all := b.rankAll()
	i, err := indexOf(all, player)
	if err != nil {
		return 0, err
	}
	return all[i].Rank, nil
}

func (b *Leaderboard) Around(player string, k int) ([]RankedEntry, error) {
	if k < 0 {
		return nil, fmt.Errorf("k must be >= 0: %w", ErrInvalidArgument)
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	all := b.rankAll()
	idx, err := indexOf(all, player)
	if err != nil {
		return nil, err
	}
	from := max(0, idx-k)
	to := min(len(all), idx+k+1)
	return all[from:to], nil
}

func (b *Leaderboard) Page(pageNumber, pageSize int) (Page, error) {
	if pageNumber < 1 || pageSize < 1 || pageSize > 100 {
		return Page{}, fmt.Errorf("bad page: %w", ErrInvalidArgument)
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	all := b.rankAll()
	start := int64(pageNumber-1) * int64(pageSize)
	from := int(min(start, int64(len(all))))
	to := int(min(start+int64(pageSize), int64(len(all))))
	return Page{PageNumber: pageNumber, PageSize: pageSize, TotalPlayers: len(all), Entries: all[from:to]}, nil
}

func (b *Leaderboard) RemovePlayer(player string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	e, ok := b.byPlayer[player]
	if !ok {
		return false
	}
	delete(b.byPlayer, player)
	b.remove(e)
	return true
}

func (b *Leaderboard) Size() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.byPlayer)
}

// ---------------------------------------------------------------- internals (call with mu held)

func (b *Leaderboard) remove(e entry) {
	i := sort.Search(len(b.ranking), func(i int) bool { return !less(b.ranking[i], e) })
	b.ranking = append(b.ranking[:i], b.ranking[i+1:]...)
}

func (b *Leaderboard) rankAll() []RankedEntry {
	out := make([]RankedEntry, 0, len(b.ranking))
	rank := 0
	for pos, e := range b.ranking {
		if pos == 0 || e.score != b.ranking[pos-1].score {
			rank = pos + 1 // ties share a rank; the next rank skips
		}
		out = append(out, RankedEntry{Rank: rank, Player: e.player, Score: e.score})
	}
	return out
}

func indexOf(all []RankedEntry, player string) (int, error) {
	for i, r := range all {
		if r.Player == player {
			return i, nil
		}
	}
	return 0, fmt.Errorf("unknown player %q: %w", player, ErrNotFound)
}
