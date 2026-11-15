#pragma once

#include "../shared/shm_protocol.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <cstdint>
#include <vector>

namespace vcam {

enum class ShmReadStatus {
    Disconnected = 0,
    Live = 1,
    Paused = 2,
};

struct ShmFrameSnapshot {
    ShmReadStatus status = ShmReadStatus::Disconnected;
    uint32_t width = 0;
    uint32_t height = 0;
    phonelink_cam::PixelFormat format = phonelink_cam::PixelFormat::Unknown;
    uint32_t stride = 0;
    int64_t timestamp100ns = 0;
    uint64_t writeSeq = 0;
    uint8_t flipFlags = 0;
    const uint8_t* data = nullptr;
    size_t dataSize = 0;
};

class ShmReader {
public:
    ShmReader() = default;
    ~ShmReader();

    ShmReader(const ShmReader&) = delete;
    ShmReader& operator=(const ShmReader&) = delete;

    void Close();
    ShmReadStatus ReadLatest(ShmFrameSnapshot& outSnapshot);

private:
    bool EnsureOpen();
    uint8_t ReadRegistryFlipFlags();
    static bool ComputeLayout(
        const phonelink_cam::ShmHeader& hdr,
        uint32_t& outStride,
        size_t& outRequiredBytes);

    HANDLE m_hMap = nullptr;
    const uint8_t* m_view = nullptr;
    size_t m_viewSize = 0;

    bool m_sawEvent = false;
    uint64_t m_lastSeq = 0;
    uint32_t m_staleTicks = 0;
    uint32_t m_disconnectedTicks = 0;
    uint32_t m_reopenCooldown = 0;
    uint32_t m_regCacheTicks = 0;
    uint8_t m_cachedRegFlipFlags = 0;

    std::vector<uint8_t> m_copyBuffer;
};

} // namespace vcam
