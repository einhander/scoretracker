#include "position/DtwMatcher.h"
#include <algorithm>
#include <cmath>
namespace temposcore {
float DtwMatcher::frameDistance(const AudioFeatureFrame& a, const ScoreFeatureFrame& b) noexcept {
    if (!a.valid) return 1e6f;
    float aa = 0, bb = 0, d = 0;
    for (int i = 0; i < 12; ++i) { aa += a.chroma[i] * a.chroma[i]; bb += b.chroma[i] * b.chroma[i]; d += a.chroma[i] * b.chroma[i]; }
    const float cosine = (aa > 0 && bb > 0) ? d / std::sqrt(aa * bb) : 0;
    constexpr float LiveOnsetScale = 100.0f;
    return .75f * (1 - cosine) + .25f * std::abs(std::min(1.0f, a.onset / LiveOnsetScale) - b.onset);
}
PositionObservation DtwMatcher::global(const FeatureRing& l) noexcept { return search(l, 0, false, 0, nullptr, 0); }
PositionObservation DtwMatcher::local(const FeatureRing& l, double p) noexcept { return search(l, p, true, 30.0, nullptr, 0); }
PositionObservation DtwMatcher::globalOn(const FeatureRing& l, const std::array<size_t, 8>& starts, size_t count) noexcept {
    return search(l, 0, false, 0, starts.data(), count);
}
PositionObservation DtwMatcher::localOn(const FeatureRing& l, double predicted, double radiusSec) noexcept {
    return search(l, predicted, true, radiusSec, nullptr, 0);
}
void DtwMatcher::coarseTopK(const FeatureRing& l, size_t stepFrames,
                            std::array<size_t, 8>& out, size_t& outCount) const noexcept {
    outCount = 0;
    const size_t n = l.copy(live_);
    if (n == 0) return;
    const auto& ref = reference_.frames();
    if (ref.empty() || stepFrames == 0) return;
    struct Cand { float score; size_t start; };
    std::array<Cand, 8> top{};
    size_t topN = 0;
    for (size_t s = 0; s < ref.size(); s += stepFrames) {
        float sum = 0.0f;
        int cnt = 0;
        for (size_t i = 0; i < n; ++i) {
            const size_t j = s + i;
            if (j >= ref.size()) break;
            float aa = 0, bb = 0, d = 0;
            for (int c = 0; c < 12; ++c) {
                aa += live_[i].chroma[c] * live_[i].chroma[c];
                bb += ref[j].chroma[c] * ref[j].chroma[c];
                d += live_[i].chroma[c] * ref[j].chroma[c];
            }
            const float cos = (aa > 0 && bb > 0) ? d / std::sqrt(aa * bb) : 0;
            sum += cos;
            ++cnt;
        }
        if (cnt < static_cast<int>(n / 2)) continue;
        const float score = sum / static_cast<float>(cnt);
        if (topN < 8) {
            top[topN++] = {score, s};
            for (size_t k = topN; k > 1 && top[k - 1].score > top[k - 2].score; --k) std::swap(top[k - 1], top[k - 2]);
        } else if (score > top[7].score) {
            top[7] = {score, s};
            for (size_t k = 7; k > 0 && top[k].score > top[k - 1].score; --k) std::swap(top[k], top[k - 1]);
        }
    }
    for (size_t i = 0; i < topN; ++i) out[i] = top[i].start;
    outCount = topN;
}
PositionObservation DtwMatcher::search(const FeatureRing& l, double predicted, bool localSearch,
                                      double radiusSec, const size_t* startList, size_t startCount) noexcept {
    const size_t n = l.copy(live_);
    PositionObservation o;
    o.globalMatch = !localSearch;
    if (!n) return o;
    const auto& ref = reference_.frames();
    size_t valid = 0;
    for (size_t i = 0; i < n; ++i) if (live_[i].valid) ++valid;
    if (valid < n / 2 || ref.empty()) return o;

    struct Cand { float cost; size_t s; };
    std::array<Cand, 16> top{};
    size_t topN = 0;
    auto consider = [&](float cost, size_t s) {
        if (topN < 16) {
            top[topN++] = {cost, s};
            for (size_t k = topN; k > 1 && top[k - 1].cost < top[k - 2].cost; --k) std::swap(top[k - 1], top[k - 2]);
        } else if (cost < top[15].cost) {
            top[15] = {cost, s};
            for (size_t k = 15; k > 0 && top[k].cost < top[k - 1].cost; --k) std::swap(top[k], top[k - 1]);
        }
    };

    const size_t nCand = (startList != nullptr) ? startCount : ref.size();
    for (size_t ci = 0; ci < nCand; ++ci) {
        const size_t s = (startList != nullptr) ? startList[ci] : ci;
        if (s >= ref.size()) continue;
        if (localSearch && std::abs(ref[s].nominalSeconds - predicted) > radiusSec) continue;
        if (ref.size() - s <= ((n - 1) * 65 / 100)) continue;
        dp_[0] = frameDistance(live_[0], ref[s]);
        for (size_t i = 1; i < n; ++i) {
            const size_t lo = i * 65 / 100, hi = std::min({i * 150 / 100, ref.size() - s - 1, MaxBand - 1});
            const size_t loPrev = (i - 1) * 65 / 100, hiPrev = std::min({(i - 1) * 150 / 100, ref.size() - s - 1, MaxBand - 1});
            std::fill(dp_.begin() + i * MaxBand + lo, dp_.begin() + i * MaxBand + hi + 1, 1e30f);
            for (size_t k = lo; k <= hi; ++k) {
                float prev = 1e30f;
                if (k <= hiPrev) prev = std::min(prev, dp_[(i - 1) * MaxBand + k]);
                if (k > 0 && k - 1 >= loPrev && k - 1 <= hiPrev) prev = std::min(prev, dp_[(i - 1) * MaxBand + k - 1]);
                if (k > lo) prev = std::min(prev, dp_[i * MaxBand + k - 1]);
                if (prev < 1e30f) dp_[i * MaxBand + k] = prev + frameDistance(live_[i], ref[s + k]);
            }
        }
        const size_t lo = (n - 1) * 65 / 100, hi = std::min({(n - 1) * 150 / 100, ref.size() - s - 1, MaxBand - 1});
        float cost = 1e30f;
        size_t end = lo;
        for (size_t k = lo; k <= hi; ++k) if (dp_[(n - 1) * MaxBand + k] < cost) { cost = dp_[(n - 1) * MaxBand + k]; end = k; }
        if (cost >= 1e30f) continue;
        if (localSearch) {
            // Bounded continuity prior: a small penalty for distance from the
            // predicted position so a match near the prediction is preferred over
            // an equally good match elsewhere (1st vs 2nd occurrence of a repeat).
            // Bounded: a clearly stronger unique match elsewhere still wins.
            cost += 0.01f * std::abs(ref[s].nominalSeconds - predicted);
        }
        cost /= static_cast<float>(n + end);
        consider(cost, s);
    }
    if (topN == 0) return o;
    const float bestCost = top[0].cost;
    const size_t best = top[0].s;
    // Non-neighbour second-best: the best candidate at least 2 s (20 frames)
    // from the best, so the ambiguity margin reflects a genuine alternative
    // (e.g. a repeated section) rather than a shifted version of the same match.
    float second = 1e30f;
    for (size_t i = 1; i < topN; ++i) {
        if (std::abs(static_cast<double>(top[i].s) - static_cast<double>(best)) >= 20.0) { second = top[i].cost; break; }
    }
    o.quarterBeatPosition = ref[best].quarterBeatPosition;
    o.matchQuality = 1 / (1 + bestCost);
    o.ambiguityMargin = std::max(0.0f, second - bestCost);
    o.confidence = o.matchQuality * std::min(1.0f, static_cast<float>(n) / 60.0f) * std::min(1.0f, o.ambiguityMargin * 10);
    o.valid = o.confidence >= .5f;
    return o;
}
}
