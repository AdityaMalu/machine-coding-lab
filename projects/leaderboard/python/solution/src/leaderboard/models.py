"""Given."""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class RankedEntry:
    rank: int
    player: str
    score: int


@dataclass(frozen=True)
class Page:
    page_number: int
    page_size: int
    total_players: int
    entries: list = field(default_factory=list)
