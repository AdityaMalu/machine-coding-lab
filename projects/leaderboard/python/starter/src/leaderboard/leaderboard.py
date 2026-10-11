import bisect

from .models import Page, RankedEntry


class _Entry:
    def __init__(self, player, score, seq):
        self.player = player
        self.score = score
        self.seq = seq


class Leaderboard:
    def __init__(self):
        self._by_player = {}
        self._ranking = []  # kept sorted, best first
        self._next_seq = 1

    @staticmethod
    def _order(entry):
        return (-entry.score, entry.player)

    def submit_score(self, player, score):
        if player is None or not player.strip():
            raise ValueError("player required")
        entry = self._by_player.get(player)
        if entry is None:
            entry = _Entry(player, score, self._next_seq)
            self._next_seq += 1
            self._by_player[player] = entry
            bisect.insort(self._ranking, entry, key=self._order)
            return True
        entry.score = score
        bisect.insort(self._ranking, entry, key=self._order)
        return True

    def top(self, n):
        if n < 0:
            raise ValueError("n must be >= 0")
        ranked = self._rank_all()
        return [ranked[i] for i in range(n)]

    def rank_of(self, player):
        for r in self._rank_all():
            if r.player == player:
                return r.rank
        raise KeyError("unknown player " + player)

    def around(self, player, k):
        if k < 0:
            raise ValueError("k must be >= 0")
        ranked = self._rank_all()
        idx = -1
        for i, r in enumerate(ranked):
            if r.player == player:
                idx = i
        if idx < 0:
            raise KeyError("unknown player " + player)
        return ranked[idx - k:idx + k]

    def page(self, page_number, page_size):
        if page_number < 1 or page_size < 1 or page_size > 100:
            raise ValueError("bad page")
        ranked = self._rank_all()
        start = page_number * page_size
        return Page(page_number, page_size, len(ranked), ranked[start:start + page_size])

    def remove_player(self, player):
        return self._by_player.pop(player, None) is not None

    def size(self):
        return len(self._by_player)

    def _rank_all(self):
        out = []
        rank = 0
        previous = None
        for e in self._ranking:
            if e.score != previous:
                rank += 1
            out.append(RankedEntry(rank, e.player, e.score))
            previous = e.score
        return out
