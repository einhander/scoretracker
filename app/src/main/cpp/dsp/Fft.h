#pragma once
#include <complex>
#include <cstddef>
#include <vector>
namespace temposcore {
class Fft final {
public:
    explicit Fft(size_t size);
    void forward(std::complex<float>* data) const noexcept;
    size_t size() const noexcept { return size_; }
private:
    size_t size_;
    std::vector<size_t> bitReverse_;
    std::vector<std::complex<float>> twiddles_;
};
}
