#pragma once

#include "shm_reader.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace vcam {

enum class TargetFormat {
    NV12 = 0,
    YUY2 = 1,
    RGB32 = 2,
    RGB24 = 3,
};

struct TargetFormatDesc {
    TargetFormat format = TargetFormat::NV12;
    uint32_t width = 1280;
    uint32_t height = 720;
    uint32_t stride = 1280;
    bool bottomUp = false; // Applies to RGB32 / RGB24 when biHeight > 0
};

class FrameConverter {
public:
    bool ConvertLiveFrame(
        const ShmFrameSnapshot& src,
        const TargetFormatDesc& dst,
        uint8_t* dstBuf,
        size_t dstBufBytes);

    bool RenderStandby(
        const TargetFormatDesc& dst,
        uint64_t frameIndex,
        bool isPaused,
        uint8_t* dstBuf,
        size_t dstBufBytes);

    static size_t RequiredBufferSize(const TargetFormatDesc& dst) noexcept;

private:
    void EnsureScratch(uint32_t width, uint32_t height);

    bool ConvertBgraToTarget(
        const uint8_t* srcBgra,
        uint32_t srcStride,
        const TargetFormatDesc& dst,
        uint8_t* dstBuf,
        size_t dstBufBytes) const;

    bool ConvertNv12ToTarget1to1(
        const ShmFrameSnapshot& src,
        const TargetFormatDesc& dst,
        uint8_t* dstBuf,
        size_t dstBufBytes) const;

    bool ConvertRgb24ToTarget1to1(
        const ShmFrameSnapshot& src,
        const TargetFormatDesc& dst,
        uint8_t* dstBuf,
        size_t dstBufBytes) const;

    void ScaleAndCenterToScratchBgra(const ShmFrameSnapshot& src, uint32_t dstW, uint32_t dstH);

    std::vector<uint8_t> m_scratchBgra;
};

} // namespace vcam
