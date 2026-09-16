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
    // Keep a larger raw pool first.  Without this, top-8 is often eight nearby
    // timestamps from ONE chorus/bar and a genuinely repeated section never
    // reaches the fine matcher, making ambiguity look artificially high.
    std::array<Cand, 64> raw{};
    size_t rawN = 0;
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
        if (rawN < raw.size()) {
            raw[rawN++] = {score, s};
            for (size_t k = rawN; k > 1 && raw[k - 1].score > raw[k - 2].score; --k)
                std::swap(raw[k - 1], raw[k - 2]);
        } else if (score > raw.back().score) {
            raw.back() = {score, s};
            for (size_t k = raw.size() - 1; k > 0 && raw[k].score > raw[k - 1].score; --k)
                std::swap(raw[k], raw[k - 1]);
        }
    }
    // Non-maximum suppression in time: each coarse candidate should represent
    // a different musical neighbourhood, not a shifted alignment of the same
    // live window.  Use ~80% of the current context (capped at 8 s).
    const size_t kMinCandidateSeparationFrames = std::max<size_t>(20, std::min<size_t>(80, n * 4 / 5));
    for (size_t i = 0; i < rawN && outCount < out.size(); ++i) {
        bool nearExisting = false;
        for (size_t j = 0; j < outCount; ++j) {
            const size_t d = raw[i].start > out[j] ? raw[i].start - out[j] : out[j] - raw[i].start;
            if (d < kMinCandidateSeparationFrames) { nearExisting = true; break; }
        }
        if (!nearExisting) out[outCount++] = raw[i].start;
    }
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

    struct Cand { float cost; size_t s; size_t current; };
    std::array<Cand, 16> top{};
    size_t topN = 0;
    const double liveSpanSeconds = n > 1 ? static_cast<double>(n - 1) / 10.0 : 0.0;
    const double hypothesisSeparationSeconds = std::max(2.0, std::min(8.0, 0.8 * liveSpanSeconds));
    auto consider = [&](float cost, size_t s, size_t current) {
        // Collapse neighbouring alignments of the same physical match.  The
        // retained alternatives must be genuinely different score locations;
        // otherwise a repeated bar/chorus can disappear from second-best.
        for (size_t i = 0; i < topN; ++i) {
            if (std::abs(ref[top[i].current].nominalSeconds - ref[current].nominalSeconds)
                    < hypothesisSeparationSeconds) {
                if (cost < top[i].cost) {
                    top[i] = {cost, s, current};
                    while (i > 0 && top[i].cost < top[i - 1].cost) {
                        std::swap(top[i], top[i - 1]);
                        --i;
                    }
                }
                return;
            }
        }
        if (topN < 16) {
            top[topN++] = {cost, s, current};
            for (size_t k = topN; k > 1 && top[k - 1].cost < top[k - 2].cost; --k)
                std::swap(top[k - 1], top[k - 2]);
        } else if (cost < top[15].cost) {
            top[15] = {cost, s, current};
            for (size_t k = 15; k > 0 && top[k].cost < top[k - 1].cost; --k)
                std::swap(top[k], top[k - 1]);
        }
    };

    // `predicted` is a musical position (quarter beats), because that is what
    // LiveTransport publishes.  Convert it to the reference nominal-time axis
    // used for the local-search radius.  The old code compared quarter beats to
    // seconds directly, which moved the local search window to the wrong place.
    double predictedSeconds = 0.0;
    if (localSearch) {
        auto it = std::lower_bound(ref.begin(), ref.end(), predicted,
            [](const ScoreFeatureFrame& f, double q) { return f.quarterBeatPosition < q; });
        if (it == ref.end()) predictedSeconds = ref.back().nominalSeconds;
        else if (it == ref.begin()) predictedSeconds = it->nominalSeconds;
        else {
            const auto prev = it - 1;
            predictedSeconds = (std::abs(it->quarterBeatPosition - predicted) <
                                std::abs(prev->quarterBeatPosition - predicted))
                ? it->nominalSeconds : prev->nominalSeconds;
        }
    }
    const size_t nCand = (startList != nullptr) ? startCount : ref.size();
    for (size_t ci = 0; ci < nCand; ++ci) {
        const size_t s = (startList != nullptr) ? startList[ci] : ci;
        if (s >= ref.size()) continue;
        if (localSearch) {
            // s is the START of the rolling live window, while predictedSeconds
            // describes "now" (the END).  Use an approximate end only as a cheap
            // pre-filter; the exact aligned end is checked after DTW below.
            const double approxEndSeconds = ref[s].nominalSeconds + liveSpanSeconds;
            const double prefilterRadius = radiusSec + 0.55 * liveSpanSeconds;
            if (std::abs(approxEndSeconds - predictedSeconds) > prefilterRadius) continue;
        }
        if (ref.size() - s <= ((n - 1) * 78 / 100)) continue;
        dp_[0] = frameDistance(live_[0], ref[s]);
        for (size_t i = 1; i < n; ++i) {
            const size_t lo = i * 78 / 100, hi = std::min({i * 130 / 100, ref.size() - s - 1, MaxBand - 1});
            const size_t loPrev = (i - 1) * 78 / 100, hiPrev = std::min({(i - 1) * 130 / 100, ref.size() - s - 1, MaxBand - 1});
            std::fill(dp_.begin() + i * MaxBand + lo, dp_.begin() + i * MaxBand + hi + 1, 1e30f);
            for (size_t k = lo; k <= hi; ++k) {
                float prev = 1e30f;
                if (k <= hiPrev) prev = std::min(prev, dp_[(i - 1) * MaxBand + k]);
                if (k > 0 && k - 1 >= loPrev && k - 1 <= hiPrev) prev = std::min(prev, dp_[(i - 1) * MaxBand + k - 1]);
                if (k > lo) prev = std::min(prev, dp_[i * MaxBand + k - 1]);
                if (prev < 1e30f) dp_[i * MaxBand + k] = prev + frameDistance(live_[i], ref[s + k]);
            }
        }
        const size_t lo = (n - 1) * 78 / 100, hi = std::min({(n - 1) * 130 / 100, ref.size() - s - 1, MaxBand - 1});
        float cost = 1e30f;
        size_t end = lo;
        for (size_t k = lo; k <= hi; ++k) if (dp_[(n - 1) * MaxBand + k] < cost) { cost = dp_[(n - 1) * MaxBand + k]; end = k; }
        if (cost >= 1e30f) continue;
        const size_t current = s + end;
        if (current >= ref.size()) continue;
        const double currentSeconds = ref[current].nominalSeconds;
        if (localSearch && std::abs(currentSeconds - predictedSeconds) > radiusSec) continue;

        cost /= static_cast<float>(n + end);
        if (localSearch) {
            // Continuity prior is applied to the aligned CURRENT position, not
            // the start of the rolling context window.
            // Repeated measures need a meaningful continuity preference.
            // Cap the penalty so a genuinely different, much stronger acoustic
            // match can still win and trigger Weak/Reacquiring.
            const double distanceSeconds = std::abs(currentSeconds - predictedSeconds);
            cost += 0.015f * static_cast<float>(std::min(8.0, distanceSeconds));
        }
        consider(cost, s, current);
    }
    if (topN == 0) return o;
    const float bestCost = top[0].cost;
    const size_t bestCurrent = top[0].current;
    // Non-neighbour second-best: compare CURRENT aligned positions.  Candidate
    // starts are an implementation detail of the rolling context window.
    float second = 1e30f;
    for (size_t i = 1; i < topN; ++i) {
        if (std::abs(ref[top[i].current].nominalSeconds - ref[bestCurrent].nominalSeconds) >= 2.0) {
            second = top[i].cost;
            break;
        }
    }
    o.quarterBeatPosition = ref[bestCurrent].quarterBeatPosition;
    o.matchQuality = 1 / (1 + bestCost);
    o.ambiguityMargin = std::max(0.0f, second - bestCost);
    o.confidence = o.matchQuality * std::min(1.0f, static_cast<float>(n) / 60.0f) * std::min(1.0f, o.ambiguityMargin * 10);
    o.valid = o.confidence >= .5f;
    return o;
}
}
