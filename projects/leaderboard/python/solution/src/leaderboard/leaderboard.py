import bisect
import threading
from dataclasses import dataclass

from .models import Page, RankedEntry


@dataclass(frozen=True)
class _Entry:
    player: str
    score: int
    seq: int  # when this score was reached; earlier wins ties


def _order(entry):
    return (-entry.score, entry.seq, entry.player)


class Leaderboard:
    """
    Fixes vs. the production version:
     - Entries are immutable. Changing the score of an entry that is already in the sorted list left it in the
       wrong place and inserted it a second time (the duplicates); now the old entry is removed first.
     - Only a strictly higher score replaces the best, and it records a new "reached at" sequence.
     - Ties are ordered by who reached the score first (seq), then name.
     - Competition ranking (1,2,2,4) instead of dense ranking (1,2,2,3).
     - top/around/page clip their ranges (Python's negative indexes wrap around); page numbers are 1-based;
       around includes k below.
     - remove_player removes from both structures; negative scores rejected; one lock around everything.
    """

    def __init__(self):
        self._by_player = {}
        self._ranking = []  # sorted by _order
        self._next_seq = 1
        self._lock = threading.Lock()

    def submit_score(self, player, score):
        if player is None or not player.strip():
            raise ValueError("player required")
        if score < 0:
            raise ValueError("score must be >= 0")
        with self._lock:
            old = self._by_player.get(player)
            if old is not None and score <= old.score:
                return False
            if old is not None:
                self._remove_entry(old)
            entry = _Entry(player, score, self._next_seq)
            self._next_seq += 1
            self._by_player[player] = entry
            bisect.insort(self._ranking, entry, key=_order)
            return True

    def top(self, n):
        if n < 0:
            raise ValueError("n must be >= 0")
        with self._lock:
            return self._rank_all()[:n]

    def rank_of(self, player):
        with self._lock:
            ranked = self._rank_all()
            return ranked[self._index_of(ranked, player)].rank

    def around(self, player, k):
        if k < 0:
            raise ValueError("k must be >= 0")
        with self._lock:
            ranked = self._rank_all()
            idx = self._index_of(ranked, player)
            return ranked[max(0, idx - k):idx + k + 1]

    def page(self, page_number, page_size):
        if page_number < 1 or page_size < 1 or page_size > 100:
            raise ValueError("bad page")
        with self._lock:
            ranked = self._rank_all()
            start = (page_number - 1) * page_size
            return Page(page_number, page_size, len(ranked), ranked[start:start + page_size])

    def remove_player(self, player):
        with self._lock:
            entry = self._by_player.pop(player, None)
            if entry is None:
                return False
            self._remove_entry(entry)
            return True

    def size(self):
        with self._lock:
            return len(self._by_player)

    # ---------------------------------------------------------------- internals (call with the lock held)

    def _remove_entry(self, entry):
        i = bisect.bisect_left(self._ranking, _order(entry), key=_order)
        del self._ranking[i]

    def _rank_all(self):
        out = []
        rank = 0
        previous = None
        for position, e in enumerate(self._ranking, start=1):
            if e.score != previous:
                rank = position  # ties share a rank; the next rank skips
            out.append(RankedEntry(rank, e.player, e.score))
            previous = e.score
        return out

    @staticmethod
    def _index_of(ranked, player):
        for i, r in enumerate(ranked):
            if r.player == player:
                return i
        raise KeyError("unknown player %s" % player)
