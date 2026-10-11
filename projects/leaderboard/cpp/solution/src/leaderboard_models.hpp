#pragma once
// Given.

#include <cstdint>
#include <string>
#include <vector>

struct RankedEntry {
    int rank;
    std::string player;
    int64_t score;
};

struct Page {
    int pageNumber, pageSize, totalPlayers;
    std::vector<RankedEntry> entries;
};
