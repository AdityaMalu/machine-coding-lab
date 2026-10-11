package leaderboard

import (
	"fmt"
	"sort"
	"strings"
)

type entry struct {
	player string
	score  int64
	seq    int64
}

type Leaderboard struct {
	byPlayer map[string]*entry
	ranking  []*entry // kept sorted, best first
	nextSeq  int64
}

func New() *Leaderboard {
	return &Leaderboard{byPlayer: map[string]*entry{}, nextSeq: 1}
}

func less(a, b *entry) bool {
	if a.score != b.score {
		return a.score > b.score
	}
	return a.player < b.player
}

func (b *Leaderboard) insert(e *entry) {
	i := sort.Search(len(b.ranking), func(i int) bool { return less(e, b.ranking[i]) })
	b.ranking = append(b.ranking, nil)
	copy(b.ranking[i+1:], b.ranking[i:])
	b.ranking[i] = e
}

func (b *Leaderboard) SubmitScore(player string, score int64) (bool, error) {
	if strings.TrimSpace(player) == "" {
		return false, fmt.Errorf("player required: %w", ErrInvalidArgument)
	}
	e, ok := b.byPlayer[player]
	if !ok {
		e = &entry{player: player, score: score, seq: b.nextSeq}
		b.nextSeq++
		b.byPlayer[player] = e
		b.insert(e)
		return true, nil
	}
	e.score = score
	b.insert(e)
	return true, nil
}

func (b *Leaderboard) Top(n int) ([]RankedEntry, error) {
	if n < 0 {
		return nil, fmt.Errorf("n must be >= 0: %w", ErrInvalidArgument)
	}
	return b.rankAll()[:n], nil
}

func (b *Leaderboard) RankOf(player string) (int, error) {
	for _, r := range b.rankAll() {
		if r.Player == player {
			return r.Rank, nil
		}
	}
	return 0, fmt.Errorf("unknown player %q: %w", player, ErrNotFound)
}

func (b *Leaderboard) Around(player string, k int) ([]RankedEntry, error) {
	if k < 0 {
		return nil, fmt.Errorf("k must be >= 0: %w", ErrInvalidArgument)
	}
	all := b.rankAll()
	idx := -1
	for i, r := range all {
		if r.Player == player {
			idx = i
		}
	}
	if idx < 0 {
		return nil, fmt.Errorf("unknown player %q: %w", player, ErrNotFound)
	}
	return all[idx-k : idx+k], nil
}

func (b *Leaderboard) Page(pageNumber, pageSize int) (Page, error) {
	if pageNumber < 1 || pageSize < 1 || pageSize > 100 {
		return Page{}, fmt.Errorf("bad page: %w", ErrInvalidArgument)
	}
	all := b.rankAll()
	from := min(pageNumber*pageSize, len(all))
	to := min(from+pageSize, len(all))
	return Page{PageNumber: pageNumber, PageSize: pageSize, TotalPlayers: len(all), Entries: all[from:to]}, nil
}

func (b *Leaderboard) RemovePlayer(player string) bool {
	_, ok := b.byPlayer[player]
	delete(b.byPlayer, player)
	return ok
}

func (b *Leaderboard) Size() int {
	return len(b.byPlayer)
}

func (b *Leaderboard) rankAll() []RankedEntry {
	out := []RankedEntry{}
	rank := 0
	previous := int64(-1 << 63)
	for _, e := range b.ranking {
		if e.score != previous {
			rank++
		}
		out = append(out, RankedEntry{Rank: rank, Player: e.player, Score: e.score})
		previous = e.score
	}
	return out
}
