#include "audio/FeatureGrid.h"
#include "dsp/Stft.h"

#include <array>
#include <cstdint>
#include <fstream>
#include <iostream>

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: temposcore_feature_grid_evidence OUTPUT.csv\n";
        return 2;
    }
    std::ofstream csv(argv[1]);
    if (!csv) {
        std::cerr << "cannot open output CSV\n";
        return 2;
    }
    csv << "sample_rate,grid,sequence,center_frame,time_seconds,delta_seconds\n";
    constexpr int durationSeconds = 180;
    constexpr int blockSize = 1024;
    std::array<float, blockSize> silence{};
    for (const int rate : {44100, 48000}) {
        temposcore::Stft stft(rate);
        temposcore::FeatureGrid fixed(rate, static_cast<int64_t>(stft.fftSize() / 2));
        int64_t legacyDeadline = static_cast<int64_t>(stft.fftSize() / 2);
        int64_t legacyCount = 0;
        int64_t fixedCount = 0;
        double previousLegacy = -1.0;
        double previousFixed = -1.0;
        uint64_t block = 0;
        while (true) {
            if (!stft.process(silence.data(), silence.size())) {
                ++block;
                continue;
            }
            const int64_t center = static_cast<int64_t>((stft.frameIndex() - 1) * stft.hop() + stft.fftSize() / 2);
            if (center >= static_cast<int64_t>(durationSeconds) * rate) break;
            const double time = static_cast<double>(center) / rate;
            if (center >= legacyDeadline) {
                csv << rate << ",legacy," << legacyCount++ << ',' << center << ',' << time << ','
                    << (previousLegacy < 0.0 ? 0.0 : time - previousLegacy) << '\n';
                previousLegacy = time;
                legacyDeadline = center + rate / 10;
            }
            if (fixed.due(center)) {
                csv << rate << ",fixed," << fixedCount++ << ',' << center << ',' << time << ','
                    << (previousFixed < 0.0 ? 0.0 : time - previousFixed) << '\n';
                previousFixed = time;
            }
            ++block;
        }
        std::cerr << "rate=" << rate << " legacy_features=" << legacyCount
                  << " fixed_features=" << fixedCount << " fixed_span_s="
                  << (previousFixed - static_cast<double>(stft.fftSize() / 2) / rate)
                  << " blocks=" << block << '\n';
    }
    return csv ? 0 : 1;
}
