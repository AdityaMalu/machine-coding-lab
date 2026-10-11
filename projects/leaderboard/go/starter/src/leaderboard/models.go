package leaderboard

// Given.

import "errors"

type RankedEntry struct {
	Rank   int
	Player string
	Score  int64
}

type Page struct {
	PageNumber, PageSize, TotalPlayers int
	Entries                            []RankedEntry
}

var (
	ErrInvalidArgument = errors.New("invalid argument")
	ErrNotFound        = errors.New("player not found")
)
