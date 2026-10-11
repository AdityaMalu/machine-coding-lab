import random
import threading

from labtest import assert_equal, assert_false, assert_raises, assert_true, run_concurrently, test
from leaderboard import Leaderboard


def names(entries):
    return [e.player for e in entries]


def ranks(entries):
    return [e.rank for e in entries]


def seven():
    """p1..p7 with scores 70, 60, ... 10."""
    b = Leaderboard()
    for i in range(1, 8):
        b.submit_score("p%d" % i, 80 - 10 * i)
    return b


class LeaderboardTest:

    @test("a lower later score never replaces the best")
    def best_score_kept(self):
        b = Leaderboard()
        assert_true(b.submit_score("ana", 100), "first score is a new best")
        assert_false(b.submit_score("ana", 50), "lower score is not a new best")
        assert_false(b.submit_score("ana", 100), "equal score is not a new best")
        assert_equal(100, b.top(1)[0].score)
        assert_true(b.submit_score("ana", 150))
        assert_equal(150, b.top(1)[0].score)

    @test("competition ranking: ties share a rank, the next rank skips (1,2,2,4)")
    def competition_ranking(self):
        b = Leaderboard()
        b.submit_score("a", 100)
        b.submit_score("b", 90)
        b.submit_score("c", 90)
        b.submit_score("d", 80)
        assert_equal([1, 2, 2, 4], ranks(b.top(4)))
        assert_equal(4, b.rank_of("d"))
        assert_equal(2, b.rank_of("c"))

    @test("equal scores: whoever reached the score first is listed first")
    def tie_order(self):
        b = Leaderboard()
        b.submit_score("zed", 900)  # zed got there first
        b.submit_score("amy", 900)
        assert_equal(["zed", "amy"], names(b.top(2)), "earlier achiever first, not alphabetical")

    @test("improving counts as reaching the new score at that moment")
    def improvement_resets_tie_time(self):
        b = Leaderboard()
        b.submit_score("amy", 500)
        b.submit_score("zed", 400)
        b.submit_score("bob", 600)
        b.submit_score("zed", 600)  # zed reaches 600 after bob
        assert_equal(["bob", "zed", "amy"], names(b.top(3)))
        b.submit_score("amy", 600)  # amy reaches 600 last
        assert_equal(["bob", "zed", "amy"], names(b.top(3)))
        assert_equal([1, 1, 1], ranks(b.top(3)))

    @test("improving many times never duplicates a player")
    def no_duplicates(self):
        b = Leaderboard()
        b.submit_score("rival", 75)
        for s in range(1, 51):
            b.submit_score("ana", s * 3)
        entries = b.top(2)
        assert_equal(["ana", "rival"], names(entries), "each player appears exactly once")
        assert_equal(2, b.size())
        assert_equal(2, b.page(1, 100).total_players, "page totals agree")
        assert_equal(["ana", "rival"], names(b.page(1, 100).entries), "no extra copies anywhere")

    @test("top(n) is clipped to the number of players")
    def top_bounds(self):
        b = seven()
        assert_equal(7, len(b.top(50)))
        assert_equal([], b.top(0))
        assert_equal(["p1", "p2", "p3"], names(b.top(3)))
        assert_raises(ValueError, lambda: b.top(-1))
        assert_equal([], Leaderboard().top(5), "empty board")

    @test("around: k above and k below, clipped at the ends")
    def around(self):
        b = seven()
        assert_equal(["p2", "p3", "p4", "p5", "p6"], names(b.around("p4", 2)), "middle")
        assert_equal(["p1", "p2", "p3"], names(b.around("p1", 2)), "near the top")
        assert_equal(["p6", "p7"], names(b.around("p7", 1)), "at the bottom")
        assert_equal(["p4"], names(b.around("p4", 0)))
        assert_raises(KeyError, lambda: b.around("ghost", 1))
        assert_raises(ValueError, lambda: b.around("p1", -1))

    @test("pages are 1-based and clipped")
    def paging(self):
        b = seven()
        first = b.page(1, 3)
        assert_equal(["p1", "p2", "p3"], names(first.entries), "page 1 starts at the top")
        assert_equal(7, first.total_players)
        assert_equal(["p7"], names(b.page(3, 3).entries), "last partial page")
        assert_equal([], b.page(4, 3).entries, "past the end")
        assert_equal([4, 5, 6], ranks(b.page(2, 3).entries))
        assert_raises(ValueError, lambda: b.page(0, 3))
        assert_raises(ValueError, lambda: b.page(1, 0))
        assert_raises(ValueError, lambda: b.page(1, 101))

    @test("removed players disappear everywhere and ranks close up")
    def remove(self):
        b = seven()
        assert_true(b.remove_player("p2"))
        assert_false(b.remove_player("p2"))
        assert_equal(6, b.size())
        assert_equal(["p1", "p3", "p4"], names(b.top(3)), "p2 must not be listed")
        assert_equal(2, b.rank_of("p3"))
        assert_raises(KeyError, lambda: b.rank_of("p2"))

    @test("validation")
    def validation(self):
        b = Leaderboard()
        assert_raises(ValueError, lambda: b.submit_score(" ", 10))
        assert_raises(ValueError, lambda: b.submit_score(None, 10))
        assert_raises(ValueError, lambda: b.submit_score("ana", -1), "negative score")
        assert_raises(KeyError, lambda: b.rank_of("nobody"))
        assert_equal(0, b.size())

    @test("concurrent submissions keep every player's true best", timeout=30)
    def concurrency(self):
        b = Leaderboard()
        expected = {}
        lock = threading.Lock()

        def worker(w):
            rnd = random.Random(w)
            for _ in range(2_000):
                player = "p%d" % rnd.randrange(10)
                score = rnd.randrange(1_000_000)
                with lock:
                    expected[player] = max(expected.get(player, -1), score)
                b.submit_score(player, score)

        run_concurrently(8, worker)
        entries = b.top(10)
        assert_equal(10, b.size(), "one entry per player")
        assert_equal(sorted(expected), sorted(names(entries)), "each player listed once")
        for e in entries:
            assert_equal(expected[e.player], e.score, "best score of " + e.player)
        for i in range(1, len(entries)):
            assert_true(entries[i - 1].score >= entries[i].score, "sorted by score")
