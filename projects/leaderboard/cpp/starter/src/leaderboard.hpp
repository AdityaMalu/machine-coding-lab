#pragma once

#include <algorithm>
#include <cctype>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include "leaderboard_models.hpp"

class Leaderboard {
public:
    bool submitScore(const std::string& player, int64_t score) {
        if (blank(player)) throw std::invalid_argument("player required");
        std::shared_ptr<Entry> existing;
        {
            std::lock_guard<std::mutex> lock(mu_);
            auto it = byPlayer_.find(player);
            if (it != byPlayer_.end()) existing = it->second;
        }
        std::lock_guard<std::mutex> lock(mu_);
        if (!existing) {
            auto e = std::make_shared<Entry>(Entry{player, score, nextSeq_++});
            byPlayer_[player] = e;
            insert(e);
            return true;
        }
        existing->score = score;
        insert(existing);
        return true;
    }

    std::vector<RankedEntry> top(int n) {
        if (n < 0) throw std::invalid_argument("n must be >= 0");
        std::vector<RankedEntry> all = rankAll();
        std::vector<RankedEntry> out;
        for (int i = 0; i < n; i++) out.push_back(all.at(i));
        return out;
    }

    int rankOf(const std::string& player) {
        for (const RankedEntry& r : rankAll()) {
            if (r.player == player) return r.rank;
        }
        throw std::out_of_range("unknown player " + player);
    }

    std::vector<RankedEntry> around(const std::string& player, int k) {
        if (k < 0) throw std::invalid_argument("k must be >= 0");
        std::vector<RankedEntry> all = rankAll();
        int idx = -1;
        for (int i = 0; i < static_cast<int>(all.size()); i++) {
            if (all[i].player == player) idx = i;
        }
        if (idx < 0) throw std::out_of_range("unknown player " + player);
        std::vector<RankedEntry> out;
        for (int i = idx - k; i < idx + k; i++) out.push_back(all.at(i));
        return out;
    }

    Page page(int pageNumber, int pageSize) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100) throw std::invalid_argument("bad page");
        std::vector<RankedEntry> all = rankAll();
        int total = static_cast<int>(all.size());
        int from = std::min(pageNumber * pageSize, total);
        int to = std::min(from + pageSize, total);
        return Page{pageNumber, pageSize, total, std::vector<RankedEntry>(all.begin() + from, all.begin() + to)};
    }

    bool removePlayer(const std::string& player) { return byPlayer_.erase(player) > 0; }

    int size() { return static_cast<int>(byPlayer_.size()); }

private:
    struct Entry {
        std::string player;
        int64_t score;
        int64_t seq;
    };

    static bool blank(const std::string& s) {
        return std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isspace(c); });
    }

    static bool before(const std::shared_ptr<Entry>& a, const std::shared_ptr<Entry>& b) {
        if (a->score != b->score) return a->score > b->score;
        return a->player < b->player;
    }

    void insert(const std::shared_ptr<Entry>& e) {
        auto pos = std::upper_bound(ranking_.begin(), ranking_.end(), e, before);
        ranking_.insert(pos, e);
    }

    std::vector<RankedEntry> rankAll() {
        std::vector<RankedEntry> out;
        int rank = 0;
        int64_t previous = std::numeric_limits<int64_t>::min();
        for (const auto& e : ranking_) {
            if (e->score != previous) rank++;
            out.push_back(RankedEntry{rank, e->player, e->score});
            previous = e->score;
        }
        return out;
    }

    std::unordered_map<std::string, std::shared_ptr<Entry>> byPlayer_;
    std::vector<std::shared_ptr<Entry>> ranking_;  // kept sorted, best first
    int64_t nextSeq_ = 1;
    std::mutex mu_;
};
