#include <map>
#include <mutex>
#include <random>
#include <string>
#include <vector>

#include "labtest.hpp"
#include "leaderboard.hpp"

namespace {

std::vector<std::string> names(const std::vector<RankedEntry>& entries) {
    std::vector<std::string> out;
    for (const auto& e : entries) out.push_back(e.player);
    return out;
}

std::vector<int> ranks(const std::vector<RankedEntry>& entries) {
    std::vector<int> out;
    for (const auto& e : entries) out.push_back(e.rank);
    return out;
}

using Names = std::vector<std::string>;
using Ranks = std::vector<int>;

// p1..p7 with scores 70, 60, ... 10.
void seven(Leaderboard& b) {
    for (int i = 1; i <= 7; i++) b.submitScore("p" + std::to_string(i), 80 - 10 * i);
}

std::string join(const std::vector<std::string>& v) {
    std::string s;
    for (const auto& x : v) s += (s.empty() ? "" : ",") + x;
    return "[" + s + "]";
}

}  // namespace

LAB_TEST(LeaderboardTest, bestScoreKept, "a lower later score never replaces the best") {
    Leaderboard b;
    ASSERT_TRUE(b.submitScore("ana", 100), "first score is a new best");
    ASSERT_FALSE(b.submitScore("ana", 50), "lower score is not a new best");
    ASSERT_FALSE(b.submitScore("ana", 100), "equal score is not a new best");
    ASSERT_EQ(int64_t{100}, b.top(1).at(0).score, "best score");
    ASSERT_TRUE(b.submitScore("ana", 150), "higher score");
    ASSERT_EQ(int64_t{150}, b.top(1).at(0).score, "new best score");
}

LAB_TEST(LeaderboardTest, competitionRanking, "competition ranking: ties share a rank, the next rank skips (1,2,2,4)") {
    Leaderboard b;
    b.submitScore("a", 100);
    b.submitScore("b", 90);
    b.submitScore("c", 90);
    b.submitScore("d", 80);
    ASSERT_TRUE(ranks(b.top(4)) == (Ranks{1, 2, 2, 4}), "ranks should be 1,2,2,4");
    ASSERT_EQ(4, b.rankOf("d"), "rank of d");
    ASSERT_EQ(2, b.rankOf("c"), "rank of c");
}

LAB_TEST(LeaderboardTest, tieOrder, "equal scores: whoever reached the score first is listed first") {
    Leaderboard b;
    b.submitScore("zed", 900);  // zed got there first
    b.submitScore("amy", 900);
    Names got = names(b.top(2));
    ASSERT_TRUE(got == (Names{"zed", "amy"}), "earlier achiever first, not alphabetical: got " + join(got));
}

LAB_TEST(LeaderboardTest, improvementResetsTieTime, "improving counts as reaching the new score at that moment") {
    Leaderboard b;
    b.submitScore("amy", 500);
    b.submitScore("zed", 400);
    b.submitScore("bob", 600);
    b.submitScore("zed", 600);  // zed reaches 600 after bob
    Names got = names(b.top(3));
    ASSERT_TRUE(got == (Names{"bob", "zed", "amy"}), "after zed improves: got " + join(got));
    b.submitScore("amy", 600);  // amy reaches 600 last
    got = names(b.top(3));
    ASSERT_TRUE(got == (Names{"bob", "zed", "amy"}), "after amy improves: got " + join(got));
    ASSERT_TRUE(ranks(b.top(3)) == (Ranks{1, 1, 1}), "all tied at rank 1");
}

LAB_TEST(LeaderboardTest, noDuplicates, "improving many times never duplicates a player") {
    Leaderboard b;
    b.submitScore("rival", 75);
    for (int s = 1; s <= 50; s++) b.submitScore("ana", s * 3);
    Page p = b.page(1, 100);
    Names got = names(p.entries);
    ASSERT_TRUE(got == (Names{"ana", "rival"}), "each player appears exactly once: got " + join(got));
    ASSERT_EQ(2, p.totalPlayers, "page total");
    ASSERT_EQ(2, b.size(), "size");
}

LAB_TEST(LeaderboardTest, topBounds, "top(n) is clipped to the number of players") {
    Leaderboard b;
    seven(b);
    ASSERT_EQ(size_t{7}, b.top(50).size(), "top(50)");
    ASSERT_TRUE(b.top(0).empty(), "top(0)");
    ASSERT_TRUE(names(b.top(3)) == (Names{"p1", "p2", "p3"}), "top(3)");
    ASSERT_THROWS(std::invalid_argument, b.top(-1), "top(-1)");
    Leaderboard empty;
    ASSERT_TRUE(empty.top(5).empty(), "empty board");
}

LAB_TEST(LeaderboardTest, around, "around: k above and k below, clipped at the ends") {
    Leaderboard b;
    seven(b);
    ASSERT_TRUE(names(b.around("p4", 2)) == (Names{"p2", "p3", "p4", "p5", "p6"}), "middle: got " + join(names(b.around("p4", 2))));
    ASSERT_TRUE(names(b.around("p1", 2)) == (Names{"p1", "p2", "p3"}), "near the top");
    ASSERT_TRUE(names(b.around("p7", 1)) == (Names{"p6", "p7"}), "at the bottom");
    ASSERT_TRUE(names(b.around("p4", 0)) == (Names{"p4"}), "k = 0");
    ASSERT_THROWS(std::out_of_range, b.around("ghost", 1), "unknown player");
    ASSERT_THROWS(std::invalid_argument, b.around("p1", -1), "negative k");
}

LAB_TEST(LeaderboardTest, paging, "pages are 1-based and clipped") {
    Leaderboard b;
    seven(b);
    Page first = b.page(1, 3);
    ASSERT_TRUE(names(first.entries) == (Names{"p1", "p2", "p3"}), "page 1 starts at the top: got " + join(names(first.entries)));
    ASSERT_EQ(7, first.totalPlayers, "totalPlayers");
    ASSERT_TRUE(names(b.page(3, 3).entries) == (Names{"p7"}), "last partial page");
    ASSERT_TRUE(b.page(4, 3).entries.empty(), "past the end");
    ASSERT_TRUE(ranks(b.page(2, 3).entries) == (Ranks{4, 5, 6}), "page 2 ranks");
    ASSERT_THROWS(std::invalid_argument, b.page(0, 3), "page 0");
    ASSERT_THROWS(std::invalid_argument, b.page(1, 0), "size 0");
    ASSERT_THROWS(std::invalid_argument, b.page(1, 101), "size 101");
}

LAB_TEST(LeaderboardTest, remove, "removed players disappear everywhere and ranks close up") {
    Leaderboard b;
    seven(b);
    ASSERT_TRUE(b.removePlayer("p2"), "first remove");
    ASSERT_FALSE(b.removePlayer("p2"), "second remove");
    ASSERT_EQ(6, b.size(), "size");
    Names got = names(b.top(3));
    ASSERT_TRUE(got == (Names{"p1", "p3", "p4"}), "p2 must not be listed: got " + join(got));
    ASSERT_EQ(2, b.rankOf("p3"), "p3 moves up");
    ASSERT_THROWS(std::out_of_range, b.rankOf("p2"), "removed player");
}

LAB_TEST(LeaderboardTest, validation, "validation") {
    Leaderboard b;
    ASSERT_THROWS(std::invalid_argument, b.submitScore(" ", 10), "blank player");
    ASSERT_THROWS(std::invalid_argument, b.submitScore("", 10), "empty player");
    ASSERT_THROWS(std::invalid_argument, b.submitScore("ana", -1), "negative score");
    ASSERT_THROWS(std::out_of_range, b.rankOf("nobody"), "unknown player");
    ASSERT_EQ(0, b.size(), "size");
}

LAB_TEST_TIMEOUT(LeaderboardTest, concurrency, "concurrent submissions keep every player's true best", 20000) {
    Leaderboard b;
    std::mutex mu;
    std::map<std::string, int64_t> expected;
    labtest::runConcurrently(8, [&](int w) {
        std::mt19937 rnd(w);
        for (int i = 0; i < 2000; i++) {
            std::string player = "p" + std::to_string(rnd() % 10);
            int64_t score = rnd() % 1'000'000;
            {
                std::lock_guard<std::mutex> lock(mu);
                auto it = expected.find(player);
                if (it == expected.end() || score > it->second) expected[player] = score;
            }
            b.submitScore(player, score);
        }
    });
    std::vector<RankedEntry> all = b.top(10);
    ASSERT_EQ(10, b.size(), "one entry per player");
    ASSERT_EQ(size_t{10}, all.size(), "ten entries listed");
    std::map<std::string, int> seen;
    for (const auto& e : all) {
        seen[e.player]++;
        ASSERT_EQ(expected[e.player], e.score, "best score of " + e.player);
    }
    ASSERT_EQ(size_t{10}, seen.size(), "each player listed once");
    for (size_t i = 1; i < all.size(); i++) ASSERT_TRUE(all[i - 1].score >= all[i].score, "sorted by score");
}
