package leaderboard

import (
	"errors"
	"fmt"
	"math/rand"
	"reflect"
	"sort"
	"sync"
	"testing"
)

// The helpers turn a panic (for example a slice out of range) into a failure of just the current test.
func noPanic(t *testing.T, what string) {
	t.Helper()
	if r := recover(); r != nil {
		t.Fatalf("%s panicked: %v", what, r)
	}
}

func top(t *testing.T, b *Leaderboard, n int) []RankedEntry {
	t.Helper()
	defer noPanic(t, fmt.Sprintf("Top(%d)", n))
	out, err := b.Top(n)
	if err != nil {
		t.Fatalf("Top(%d) failed: %v", n, err)
	}
	return out
}

func around(t *testing.T, b *Leaderboard, player string, k int) []RankedEntry {
	t.Helper()
	defer noPanic(t, fmt.Sprintf("Around(%q, %d)", player, k))
	out, err := b.Around(player, k)
	if err != nil {
		t.Fatalf("Around(%q, %d) failed: %v", player, k, err)
	}
	return out
}

func page(t *testing.T, b *Leaderboard, number, size int) Page {
	t.Helper()
	defer noPanic(t, fmt.Sprintf("Page(%d, %d)", number, size))
	p, err := b.Page(number, size)
	if err != nil {
		t.Fatalf("Page(%d, %d) failed: %v", number, size, err)
	}
	return p
}

func rankOf(t *testing.T, b *Leaderboard, player string) int {
	t.Helper()
	r, err := b.RankOf(player)
	if err != nil {
		t.Fatalf("RankOf(%q) failed: %v", player, err)
	}
	return r
}

func submit(t *testing.T, b *Leaderboard, player string, score int64) bool {
	t.Helper()
	ok, err := b.SubmitScore(player, score)
	if err != nil {
		t.Fatalf("SubmitScore(%q, %d) failed: %v", player, score, err)
	}
	return ok
}

func names(entries []RankedEntry) []string {
	out := []string{}
	for _, e := range entries {
		out = append(out, e.Player)
	}
	return out
}

func ranks(entries []RankedEntry) []int {
	out := []int{}
	for _, e := range entries {
		out = append(out, e.Rank)
	}
	return out
}

func equal[T any](t *testing.T, want, got T, msg string) {
	t.Helper()
	if !reflect.DeepEqual(want, got) {
		t.Fatalf("%s: got %v, want %v", msg, got, want)
	}
}

// seven: p1..p7 with scores 70, 60, ... 10.
func seven(t *testing.T) *Leaderboard {
	b := New()
	for i := 1; i <= 7; i++ {
		submit(t, b, fmt.Sprintf("p%d", i), int64(80-10*i))
	}
	return b
}

// Test: a lower later score never replaces the best
func TestBestScoreKept(t *testing.T) {
	b := New()
	equal(t, true, submit(t, b, "ana", 100), "first score is a new best")
	equal(t, false, submit(t, b, "ana", 50), "lower score is not a new best")
	equal(t, false, submit(t, b, "ana", 100), "equal score is not a new best")
	equal(t, int64(100), top(t, b, 1)[0].Score, "best score")
	equal(t, true, submit(t, b, "ana", 150), "higher score")
	equal(t, int64(150), top(t, b, 1)[0].Score, "new best score")
}

// Test: competition ranking: ties share a rank, the next rank skips (1,2,2,4)
func TestCompetitionRanking(t *testing.T) {
	b := New()
	submit(t, b, "a", 100)
	submit(t, b, "b", 90)
	submit(t, b, "c", 90)
	submit(t, b, "d", 80)
	equal(t, []int{1, 2, 2, 4}, ranks(top(t, b, 4)), "ranks")
	equal(t, 4, rankOf(t, b, "d"), "rank of d")
	equal(t, 2, rankOf(t, b, "c"), "rank of c")
}

// Test: equal scores: whoever reached the score first is listed first
func TestTieOrder(t *testing.T) {
	b := New()
	submit(t, b, "zed", 900) // zed got there first
	submit(t, b, "amy", 900)
	equal(t, []string{"zed", "amy"}, names(top(t, b, 2)), "earlier achiever first, not alphabetical")
}

// Test: improving counts as reaching the new score at that moment
func TestImprovementResetsTieTime(t *testing.T) {
	b := New()
	submit(t, b, "amy", 500)
	submit(t, b, "zed", 400)
	submit(t, b, "bob", 600)
	submit(t, b, "zed", 600) // zed reaches 600 after bob
	equal(t, []string{"bob", "zed", "amy"}, names(top(t, b, 3)), "after zed improves")
	submit(t, b, "amy", 600) // amy reaches 600 last
	equal(t, []string{"bob", "zed", "amy"}, names(top(t, b, 3)), "after amy improves")
	equal(t, []int{1, 1, 1}, ranks(top(t, b, 3)), "all tied")
}

// Test: improving many times never duplicates a player
func TestNoDuplicates(t *testing.T) {
	b := New()
	submit(t, b, "rival", 75)
	for s := int64(1); s <= 50; s++ {
		submit(t, b, "ana", s*3)
	}
	p := page(t, b, 1, 100)
	equal(t, []string{"ana", "rival"}, names(p.Entries), "each player appears exactly once")
	equal(t, 2, p.TotalPlayers, "page total")
	equal(t, 2, b.Size(), "Size")
}

// Test: Top(n) is clipped to the number of players
func TestTopBounds(t *testing.T) {
	b := seven(t)
	equal(t, 7, len(top(t, b, 50)), "Top(50)")
	equal(t, 0, len(top(t, b, 0)), "Top(0)")
	equal(t, []string{"p1", "p2", "p3"}, names(top(t, b, 3)), "Top(3)")
	if _, err := b.Top(-1); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("Top(-1): want ErrInvalidArgument, got %v", err)
	}
	equal(t, 0, len(top(t, New(), 5)), "empty board")
}

// Test: Around: k above and k below, clipped at the ends
func TestAround(t *testing.T) {
	b := seven(t)
	equal(t, []string{"p2", "p3", "p4", "p5", "p6"}, names(around(t, b, "p4", 2)), "middle")
	equal(t, []string{"p1", "p2", "p3"}, names(around(t, b, "p1", 2)), "near the top")
	equal(t, []string{"p6", "p7"}, names(around(t, b, "p7", 1)), "at the bottom")
	equal(t, []string{"p4"}, names(around(t, b, "p4", 0)), "k = 0")
	if _, err := b.Around("ghost", 1); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Around(ghost): want ErrNotFound, got %v", err)
	}
	if _, err := b.Around("p1", -1); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("Around(p1, -1): want ErrInvalidArgument, got %v", err)
	}
}

// Test: pages are 1-based and clipped
func TestPaging(t *testing.T) {
	b := seven(t)
	first := page(t, b, 1, 3)
	equal(t, []string{"p1", "p2", "p3"}, names(first.Entries), "page 1 starts at the top")
	equal(t, 7, first.TotalPlayers, "TotalPlayers")
	equal(t, []string{"p7"}, names(page(t, b, 3, 3).Entries), "last partial page")
	equal(t, 0, len(page(t, b, 4, 3).Entries), "past the end")
	equal(t, []int{4, 5, 6}, ranks(page(t, b, 2, 3).Entries), "page 2 ranks")
	for _, bad := range [][2]int{{0, 3}, {1, 0}, {1, 101}} {
		if _, err := b.Page(bad[0], bad[1]); !errors.Is(err, ErrInvalidArgument) {
			t.Fatalf("Page%v: want ErrInvalidArgument, got %v", bad, err)
		}
	}
}

// Test: removed players disappear everywhere and ranks close up
func TestRemove(t *testing.T) {
	b := seven(t)
	equal(t, true, b.RemovePlayer("p2"), "first remove")
	equal(t, false, b.RemovePlayer("p2"), "second remove")
	equal(t, 6, b.Size(), "Size")
	equal(t, []string{"p1", "p3", "p4"}, names(top(t, b, 3)), "p2 must not be listed")
	equal(t, 2, rankOf(t, b, "p3"), "p3 moves up")
	if _, err := b.RankOf("p2"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("RankOf(p2): want ErrNotFound, got %v", err)
	}
}

// Test: validation
func TestValidation(t *testing.T) {
	b := New()
	if _, err := b.SubmitScore(" ", 10); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("blank player: want ErrInvalidArgument, got %v", err)
	}
	if _, err := b.SubmitScore("ana", -1); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("negative score: want ErrInvalidArgument, got %v", err)
	}
	if _, err := b.RankOf("nobody"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown player: want ErrNotFound, got %v", err)
	}
	equal(t, 0, b.Size(), "Size")
}

// Test: concurrent submissions keep every player's true best
func TestConcurrency(t *testing.T) {
	b := New()
	var mu sync.Mutex
	expected := map[string]int64{}
	var wg sync.WaitGroup
	for w := 0; w < 8; w++ {
		wg.Add(1)
		go func(seed int64) {
			defer wg.Done()
			rnd := rand.New(rand.NewSource(seed))
			for i := 0; i < 2_000; i++ {
				player := fmt.Sprintf("p%d", rnd.Intn(10))
				score := int64(rnd.Intn(1_000_000))
				mu.Lock()
				if score > expected[player] {
					expected[player] = score
				}
				mu.Unlock()
				b.SubmitScore(player, score)
			}
		}(int64(w))
	}
	wg.Wait()
	all := top(t, b, 10)
	equal(t, 10, b.Size(), "one entry per player")
	got := names(all)
	sort.Strings(got)
	want := []string{}
	for p := range expected {
		want = append(want, p)
	}
	sort.Strings(want)
	equal(t, want, got, "each player listed once")
	for _, e := range all {
		equal(t, expected[e.Player], e.Score, "best score of "+e.Player)
	}
	for i := 1; i < len(all); i++ {
		if all[i-1].Score < all[i].Score {
			t.Fatal("not sorted by score")
		}
	}
}
