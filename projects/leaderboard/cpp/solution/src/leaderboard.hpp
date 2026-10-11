#pragma once

#include <algorithm>
#include <cctype>
#include <mutex>
#include <stdexcept>
#include <string>
#include <tuple>
#include <unordered_map>
#include <vector>

#include "leaderboard_models.hpp"

// Fixes vs. the production version:
//  - Entries are values that never change in place. Changing the score of an entry that was already in the sorted
//    vector left it in the wrong position and inserted it a second time (the duplicates); now the old entry is
//    removed first.
//  - Only a strictly higher score replaces the best, and it records a new "reached at" sequence.
//  - Ties are ordered by who reached the score first (seq), then name.
//  - Competition ranking (1,2,2,4) instead of dense ranking (1,2,2,3).
//  - top/around/page clip their ranges (the old index math threw out_of_range); page numbers are 1-based;
//    around includes k below.
//  - removePlayer removes from both structures; negative scores rejected; one mutex around everything.
class Leaderboard {
public:
    bool submitScore(const std::string& player, int64_t score) {
        if (blank(player)) throw std::invalid_argument("player required");
        if (score < 0) throw std::invalid_argument("score must be >= 0");
        std::lock_guard<std::mutex> lock(mu_);
        auto it = byPlayer_.find(player);
        if (it != byPlayer_.end() && score <= it->second.score) return false;
        if (it != byPlayer_.end()) erase(it->second);
        Entry e{player, score, nextSeq_++};
        byPlayer_[player] = e;
        ranking_.insert(std::lower_bound(ranking_.begin(), ranking_.end(), e, before), e);
        return true;
    }

    std::vector<RankedEntry> top(int n) {
        if (n < 0) throw std::invalid_argument("n must be >= 0");
        std::lock_guard<std::mutex> lock(mu_);
        std::vector<RankedEntry> all = rankAll();
        all.resize(std::min(all.size(), static_cast<size_t>(n)));
        return all;
    }

    int rankOf(const std::string& player) {
        std::lock_guard<std::mutex> lock(mu_);
        std::vector<RankedEntry> all = rankAll();
        return all[indexOf(all, player)].rank;
    }

    std::vector<RankedEntry> around(const std::string& player, int k) {
        if (k < 0) throw std::invalid_argument("k must be >= 0");
        std::lock_guard<std::mutex> lock(mu_);
        std::vector<RankedEntry> all = rankAll();
        long long idx = static_cast<long long>(indexOf(all, player));
        long long from = std::max(0LL, idx - k);
        long long to = std::min(static_cast<long long>(all.size()), idx + k + 1);
        return std::vector<RankedEntry>(all.begin() + from, all.begin() + to);
    }

    Page page(int pageNumber, int pageSize) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100) throw std::invalid_argument("bad page");
        std::lock_guard<std::mutex> lock(mu_);
        std::vector<RankedEntry> all = rankAll();
        long long total = static_cast<long long>(all.size());
        long long start = static_cast<long long>(pageNumber - 1) * pageSize;
        long long from = std::min(start, total);
        long long to = std::min(start + pageSize, total);
        return Page{pageNumber, pageSize, static_cast<int>(total),
                    std::vector<RankedEntry>(all.begin() + from, all.begin() + to)};
    }

    bool removePlayer(const std::string& player) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = byPlayer_.find(player);
        if (it == byPlayer_.end()) return false;
        erase(it->second);
        byPlayer_.erase(it);
        return true;
    }

    int size() {
        std::lock_guard<std::mutex> lock(mu_);
        return static_cast<int>(byPlayer_.size());
    }

private:
    struct Entry {
        std::string player;
        int64_t score = 0;
        int64_t seq = 0;  // when this score was reached; earlier wins ties
    };

    static bool blank(const std::string& s) {
        return std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isspace(c); });
    }

    static bool before(const Entry& a, const Entry& b) {
        return std::make_tuple(-a.score, a.seq, a.player) < std::make_tuple(-b.score, b.seq, b.player);
    }

    // ---- internals: call with mu_ held

    void erase(const Entry& e) {
        auto pos = std::lower_bound(ranking_.begin(), ranking_.end(), e, before);
        ranking_.erase(pos);
    }

    std::vector<RankedEntry> rankAll() const {
        std::vector<RankedEntry> out;
        out.reserve(ranking_.size());
        int rank = 0;
        for (size_t pos = 0; pos < ranking_.size(); pos++) {
            if (pos == 0 || ranking_[pos].score != ranking_[pos - 1].score) rank = static_cast<int>(pos) + 1;
            out.push_back(RankedEntry{rank, ranking_[pos].player, ranking_[pos].score});
        }
        return out;
    }

    static size_t indexOf(const std::vector<RankedEntry>& all, const std::string& player) {
        for (size_t i = 0; i < all.size(); i++) {
            if (all[i].player == player) return i;
        }
        throw std::out_of_range("unknown player " + player);
    }

    std::mutex mu_;
    std::unordered_map<std::string, Entry> byPlayer_;
    std::vector<Entry> ranking_;  // sorted by before()
    int64_t nextSeq_ = 1;
};
