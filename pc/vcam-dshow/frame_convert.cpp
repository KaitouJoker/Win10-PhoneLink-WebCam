#include "frame_convert.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace vcam {

namespace {

inline uint8_t ClampU8(int v) noexcept {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return static_cast<uint8_t>(v);
}

inline void RgbToYuv601(int r, int g, int b, uint8_t& y, uint8_t& u, uint8_t& v) noexcept {
    y = ClampU8(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
    u = ClampU8(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
    v = ClampU8(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
}

inline uint8_t RgbToY601(int r, int g, int b) noexcept {
    return ClampU8(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
}

inline void Yuv601ToRgb(uint8_t y, uint8_t u, uint8_t v, uint8_t& r, uint8_t& g, uint8_t& b) noexcept {
    const int c = static_cast<int>(y) - 16;
    const int d = static_cast<int>(u) - 128;
    const int e = static_cast<int>(v) - 128;
    r = ClampU8((298 * c + 409 * e + 128) >> 8);
    g = ClampU8((298 * c - 100 * d - 208 * e + 128) >> 8);
    b = ClampU8((298 * c + 516 * d + 128) >> 8);
}

inline void FetchSrcPixelBgra(
    const ShmFrameSnapshot& src,
    uint32_t x,
    uint32_t y,
    uint8_t& b,
    uint8_t& g,
    uint8_t& r) noexcept {
    switch (src.format) {
    case phonelink_cam::PixelFormat::BGRA32: {
        const uint8_t* p = src.data + static_cast<size_t>(y) * src.stride + static_cast<size_t>(x) * 4u;
        b = p[0];
        g = p[1];
        r = p[2];
        break;
    }
    case phonelink_cam::PixelFormat::RGB24: {
        const uint8_t* p = src.data + static_cast<size_t>(y) * src.stride + static_cast<size_t>(x) * 3u;
        r = p[0];
        g = p[1];
        b = p[2];
        break;
    }
    case phonelink_cam::PixelFormat::NV12: {
        const uint8_t* yPlane = src.data;
        const uint8_t* uvPlane = src.data + static_cast<size_t>(src.stride) * src.height;
        const uint8_t yVal = yPlane[static_cast<size_t>(y) * src.stride + x];
        const size_t uvOff = static_cast<size_t>(y >> 1) * src.stride + (x & ~1u);
        Yuv601ToRgb(yVal, uvPlane[uvOff], uvPlane[uvOff + 1], r, g, b);
        break;
    }
    default:
        b = g = r = 0;
        break;
    }
}

// Compact 5x7 uppercase ASCII bitmap font (each row is 5 LSBs, top row first).
struct Glyph5x7 {
    char ch;
    uint8_t rows[7];
};

constexpr Glyph5x7 kGlyphTable[] = {
    {' ', {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {'&', {0x0C, 0x12, 0x14, 0x08, 0x15, 0x12, 0x0D}},
    {'/', {0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10}},
    {'.', {0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C}},
    {':', {0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00}},
    {'-', {0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00}},
    {'0', {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}},
    {'1', {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}},
    {'2', {0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F}},
    {'3', {0x1E, 0x01, 0x01, 0x0E, 0x01, 0x01, 0x1E}},
    {'4', {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}},
    {'5', {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}},
    {'6', {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}},
    {'7', {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}},
    {'8', {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}},
    {'9', {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}},
    {'A', {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}},
    {'B', {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}},
    {'C', {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}},
    {'D', {0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E}},
    {'E', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}},
    {'F', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}},
    {'G', {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}},
    {'H', {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}},
    {'I', {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E}},
    {'J', {0x07, 0x02, 0x02, 0x02, 0x12, 0x12, 0x0C}},
    {'K', {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}},
    {'L', {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}},
    {'M', {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}},
    {'N', {0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11}},
    {'O', {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}},
    {'P', {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}},
    {'Q', {0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D}},
    {'R', {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}},
    {'S', {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}},
    {'T', {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}},
    {'U', {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}},
    {'V', {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}},
    {'W', {0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11}},
    {'X', {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}},
    {'Y', {0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04}},
    {'Z', {0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F}},
};

const uint8_t* LookupGlyph(char c) noexcept {
    if (c >= 'a' && c <= 'z') {
        c = static_cast<char>(c - 'a' + 'A');
    }
    for (const auto& entry : kGlyphTable) {
        if (entry.ch == c) {
            return entry.rows;
        }
    }
    return kGlyphTable[0].rows;
}

void FillRectBgra(
    uint8_t* buf,
    uint32_t bufW,
    uint32_t bufH,
    int x0,
    int y0,
    int w,
    int h,
    uint8_t b,
    uint8_t g,
    uint8_t r) noexcept {
    const int x1 = std::min(static_cast<int>(bufW), x0 + w);
    const int y1 = std::min(static_cast<int>(bufH), y0 + h);
    const int sx = std::max(0, x0);
    const int sy = std::max(0, y0);
    for (int y = sy; y < y1; ++y) {
        uint8_t* row = buf + static_cast<size_t>(y) * bufW * 4u;
        for (int x = sx; x < x1; ++x) {
            uint8_t* px = row + static_cast<size_t>(x) * 4u;
            px[0] = b;
            px[1] = g;
            px[2] = r;
            px[3] = 255;
        }
    }
}

void DrawTextCenteredBgra(
    uint8_t* buf,
    uint32_t bufW,
    uint32_t bufH,
    const char* text,
    int centerY,
    int scale,
    uint8_t b,
    uint8_t g,
    uint8_t r) noexcept {
    const size_t len = std::strlen(text);
    if (len == 0 || scale <= 0) return;

    const int charAdvance = 6 * scale;
    const int totalW = static_cast<int>(len) * charAdvance - scale;
    const int startX = (static_cast<int>(bufW) - totalW) / 2;
    const int startY = centerY - (7 * scale) / 2;

    for (size_t i = 0; i < len; ++i) {
        const uint8_t* rows = LookupGlyph(text[i]);
        const int gx = startX + static_cast<int>(i) * charAdvance;
        for (int row = 0; row < 7; ++row) {
            const uint8_t bits = rows[row];
            for (int col = 0; col < 5; ++col) {
                if ((bits & (1u << (4 - col))) != 0) {
                    FillRectBgra(buf, bufW, bufH, gx + col * scale, startY + row * scale, scale, scale, b, g, r);
                }
            }
        }
    }
}

void FlipBgraBufferInPlace(uint8_t* buf, uint32_t w, uint32_t h, bool flipH, bool flipV) noexcept {
    if ((!flipH && !flipV) || buf == nullptr || w == 0 || h == 0) {
        return;
    }
    auto* pixels = reinterpret_cast<uint32_t*>(buf);
    if (flipV && flipH) {
        const size_t total = static_cast<size_t>(w) * h;
        for (size_t i = 0; i < total / 2u; ++i) {
            std::swap(pixels[i], pixels[total - 1u - i]);
        }
        return;
    }
    if (flipV) {
        for (uint32_t y = 0; y < h / 2u; ++y) {
            uint32_t* top = pixels + static_cast<size_t>(y) * w;
            uint32_t* bot = pixels + static_cast<size_t>(h - 1u - y) * w;
            std::swap_ranges(top, top + w, bot);
        }
        return;
    }
    for (uint32_t y = 0; y < h; ++y) {
        uint32_t* row = pixels + static_cast<size_t>(y) * w;
        std::reverse(row, row + w);
    }
}

} // namespace

size_t FrameConverter::RequiredBufferSize(const TargetFormatDesc& dst) noexcept {
    switch (dst.format) {
    case TargetFormat::NV12:
        return static_cast<size_t>(dst.stride) * dst.height +
               static_cast<size_t>(dst.stride) * (dst.height / 2u);
    case TargetFormat::YUY2:
        return static_cast<size_t>(dst.stride) * dst.height;
    case TargetFormat::RGB32:
        return static_cast<size_t>(dst.stride) * dst.height;
    case TargetFormat::RGB24:
        return static_cast<size_t>(dst.stride) * dst.height;
    default:
        return 0;
    }
}

void FrameConverter::EnsureScratch(uint32_t width, uint32_t height) {
    const size_t needed = static_cast<size_t>(width) * height * 4u;
    if (m_scratchBgra.size() < needed) {
        m_scratchBgra.resize(needed);
    }
}

bool FrameConverter::ConvertBgraToTarget(
    const uint8_t* srcBgra,
    uint32_t srcStride,
    const TargetFormatDesc& dst,
    uint8_t* dstBuf,
    size_t dstBufBytes) const {
    const size_t req = RequiredBufferSize(dst);
    if (dstBuf == nullptr || dstBufBytes < req) {
        return false;
    }

    const uint32_t w = dst.width;
    const uint32_t h = dst.height;

    switch (dst.format) {
    case TargetFormat::RGB32: {
        const size_t rowBytes = static_cast<size_t>(w) * 4u;
        for (uint32_t y = 0; y < h; ++y) {
            const uint32_t dstY = dst.bottomUp ? (h - 1u - y) : y;
            const uint8_t* srcRow = srcBgra + static_cast<size_t>(y) * srcStride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(dstY) * dst.stride;
            std::memcpy(dstRow, srcRow, rowBytes);
        }
        return true;
    }

    case TargetFormat::RGB24: {
        for (uint32_t y = 0; y < h; ++y) {
            const uint32_t dstY = dst.bottomUp ? (h - 1u - y) : y;
            const uint8_t* srcRow = srcBgra + static_cast<size_t>(y) * srcStride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(dstY) * dst.stride;
            for (uint32_t x = 0; x < w; ++x) {
                dstRow[x * 3u + 0] = srcRow[x * 4u + 0]; // B
                dstRow[x * 3u + 1] = srcRow[x * 4u + 1]; // G
                dstRow[x * 3u + 2] = srcRow[x * 4u + 2]; // R
            }
        }
        return true;
    }

    case TargetFormat::YUY2: {
        for (uint32_t y = 0; y < h; ++y) {
            const uint8_t* srcRow = srcBgra + static_cast<size_t>(y) * srcStride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(y) * dst.stride;
            for (uint32_t x = 0; x < w; x += 2) {
                const uint8_t* p0 = srcRow + static_cast<size_t>(x) * 4u;
                const uint8_t* p1 = p0 + 4;
                const int b0 = p0[0], g0 = p0[1], r0 = p0[2];
                const int b1 = p1[0], g1 = p1[1], r1 = p1[2];
                const int rAvg = (r0 + r1 + 1) >> 1;
                const int gAvg = (g0 + g1 + 1) >> 1;
                const int bAvg = (b0 + b1 + 1) >> 1;
                uint8_t yDummy = 0, u = 128, v = 128;
                RgbToYuv601(rAvg, gAvg, bAvg, yDummy, u, v);
                dstRow[x * 2u + 0] = RgbToY601(r0, g0, b0);
                dstRow[x * 2u + 1] = u;
                dstRow[x * 2u + 2] = RgbToY601(r1, g1, b1);
                dstRow[x * 2u + 3] = v;
            }
        }
        return true;
    }

    case TargetFormat::NV12: {
        uint8_t* yPlane = dstBuf;
        uint8_t* uvPlane = dstBuf + static_cast<size_t>(dst.stride) * h;

        for (uint32_t y = 0; y < h; y += 2) {
            const uint8_t* s0 = srcBgra + static_cast<size_t>(y) * srcStride;
            const uint8_t* s1 = srcBgra + static_cast<size_t>(y + 1u) * srcStride;
            uint8_t* y0Row = yPlane + static_cast<size_t>(y) * dst.stride;
            uint8_t* y1Row = yPlane + static_cast<size_t>(y + 1u) * dst.stride;
            uint8_t* uvRow = uvPlane + static_cast<size_t>(y >> 1) * dst.stride;

            for (uint32_t x = 0; x < w; x += 2) {
                const uint8_t* p00 = s0 + static_cast<size_t>(x) * 4u;
                const uint8_t* p01 = p00 + 4;
                const uint8_t* p10 = s1 + static_cast<size_t>(x) * 4u;
                const uint8_t* p11 = p10 + 4;

                y0Row[x] = RgbToY601(p00[2], p00[1], p00[0]);
                y0Row[x + 1] = RgbToY601(p01[2], p01[1], p01[0]);
                y1Row[x] = RgbToY601(p10[2], p10[1], p10[0]);
                y1Row[x + 1] = RgbToY601(p11[2], p11[1], p11[0]);

                const int rAvg = (p00[2] + p01[2] + p10[2] + p11[2] + 2) >> 2;
                const int gAvg = (p00[1] + p01[1] + p10[1] + p11[1] + 2) >> 2;
                const int bAvg = (p00[0] + p01[0] + p10[0] + p11[0] + 2) >> 2;
                uint8_t yDummy = 0, u = 128, v = 128;
                RgbToYuv601(rAvg, gAvg, bAvg, yDummy, u, v);
                uvRow[x] = u;
                uvRow[x + 1] = v;
            }
        }
        return true;
    }

    default:
        return false;
    }
}

bool FrameConverter::ConvertNv12ToTarget1to1(
    const ShmFrameSnapshot& src,
    const TargetFormatDesc& dst,
    uint8_t* dstBuf,
    size_t dstBufBytes) const {
    const size_t req = RequiredBufferSize(dst);
    if (dstBuf == nullptr || dstBufBytes < req) {
        return false;
    }

    const uint32_t w = dst.width;
    const uint32_t h = dst.height;
    const uint8_t* srcY = src.data;
    const uint8_t* srcUV = src.data + static_cast<size_t>(src.stride) * src.height;

    switch (dst.format) {
    case TargetFormat::NV12: {
        uint8_t* dstY = dstBuf;
        uint8_t* dstUV = dstBuf + static_cast<size_t>(dst.stride) * h;
        if (src.stride == dst.stride) {
            std::memcpy(dstY, srcY, static_cast<size_t>(dst.stride) * h);
            std::memcpy(dstUV, srcUV, static_cast<size_t>(dst.stride) * (h / 2u));
        } else {
            for (uint32_t y = 0; y < h; ++y) {
                std::memcpy(dstY + static_cast<size_t>(y) * dst.stride,
                            srcY + static_cast<size_t>(y) * src.stride,
                            w);
            }
            for (uint32_t y = 0; y < h / 2u; ++y) {
                std::memcpy(dstUV + static_cast<size_t>(y) * dst.stride,
                            srcUV + static_cast<size_t>(y) * src.stride,
                            w);
            }
        }
        return true;
    }

    case TargetFormat::YUY2: {
        for (uint32_t y = 0; y < h; ++y) {
            const uint8_t* yRow = srcY + static_cast<size_t>(y) * src.stride;
            const uint8_t* uvRow = srcUV + static_cast<size_t>(y >> 1) * src.stride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(y) * dst.stride;
            for (uint32_t x = 0; x < w; x += 2) {
                dstRow[x * 2u + 0] = yRow[x];
                dstRow[x * 2u + 1] = uvRow[x];
                dstRow[x * 2u + 2] = yRow[x + 1];
                dstRow[x * 2u + 3] = uvRow[x + 1];
            }
        }
        return true;
    }

    case TargetFormat::RGB32: {
        for (uint32_t y = 0; y < h; ++y) {
            const uint32_t dstY = dst.bottomUp ? (h - 1u - y) : y;
            const uint8_t* yRow = srcY + static_cast<size_t>(y) * src.stride;
            const uint8_t* uvRow = srcUV + static_cast<size_t>(y >> 1) * src.stride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(dstY) * dst.stride;
            for (uint32_t x = 0; x < w; x += 2) {
                const uint8_t u = uvRow[x];
                const uint8_t v = uvRow[x + 1];
                uint8_t r0 = 0, g0 = 0, b0 = 0;
                uint8_t r1 = 0, g1 = 0, b1 = 0;
                Yuv601ToRgb(yRow[x], u, v, r0, g0, b0);
                Yuv601ToRgb(yRow[x + 1], u, v, r1, g1, b1);
                uint8_t* p0 = dstRow + static_cast<size_t>(x) * 4u;
                p0[0] = b0;
                p0[1] = g0;
                p0[2] = r0;
                p0[3] = 255;
                p0[4] = b1;
                p0[5] = g1;
                p0[6] = r1;
                p0[7] = 255;
            }
        }
        return true;
    }

    case TargetFormat::RGB24: {
        for (uint32_t y = 0; y < h; ++y) {
            const uint32_t dstY = dst.bottomUp ? (h - 1u - y) : y;
            const uint8_t* yRow = srcY + static_cast<size_t>(y) * src.stride;
            const uint8_t* uvRow = srcUV + static_cast<size_t>(y >> 1) * src.stride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(dstY) * dst.stride;
            for (uint32_t x = 0; x < w; x += 2) {
                const uint8_t u = uvRow[x];
                const uint8_t v = uvRow[x + 1];
                uint8_t r0 = 0, g0 = 0, b0 = 0;
                uint8_t r1 = 0, g1 = 0, b1 = 0;
                Yuv601ToRgb(yRow[x], u, v, r0, g0, b0);
                Yuv601ToRgb(yRow[x + 1], u, v, r1, g1, b1);
                uint8_t* p0 = dstRow + static_cast<size_t>(x) * 3u;
                p0[0] = b0;
                p0[1] = g0;
                p0[2] = r0;
                p0[3] = b1;
                p0[4] = g1;
                p0[5] = r1;
            }
        }
        return true;
    }

    default:
        return false;
    }
}

bool FrameConverter::ConvertRgb24ToTarget1to1(
    const ShmFrameSnapshot& src,
    const TargetFormatDesc& dst,
    uint8_t* dstBuf,
    size_t dstBufBytes) const {
    const size_t req = RequiredBufferSize(dst);
    if (dstBuf == nullptr || dstBufBytes < req) {
        return false;
    }

    const uint32_t w = dst.width;
    const uint32_t h = dst.height;

    if (dst.format == TargetFormat::RGB32) {
        for (uint32_t y = 0; y < h; ++y) {
            const uint32_t dstY = dst.bottomUp ? (h - 1u - y) : y;
            const uint8_t* srcRow = src.data + static_cast<size_t>(y) * src.stride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(dstY) * dst.stride;
            for (uint32_t x = 0; x < w; ++x) {
                dstRow[x * 4u + 0] = srcRow[x * 3u + 2]; // B
                dstRow[x * 4u + 1] = srcRow[x * 3u + 1]; // G
                dstRow[x * 4u + 2] = srcRow[x * 3u + 0]; // R
                dstRow[x * 4u + 3] = 255;
            }
        }
        return true;
    }

    if (dst.format == TargetFormat::RGB24) {
        for (uint32_t y = 0; y < h; ++y) {
            const uint32_t dstY = dst.bottomUp ? (h - 1u - y) : y;
            const uint8_t* srcRow = src.data + static_cast<size_t>(y) * src.stride;
            uint8_t* dstRow = dstBuf + static_cast<size_t>(dstY) * dst.stride;
            for (uint32_t x = 0; x < w; ++x) {
                dstRow[x * 3u + 0] = srcRow[x * 3u + 2]; // B
                dstRow[x * 3u + 1] = srcRow[x * 3u + 1]; // G
                dstRow[x * 3u + 2] = srcRow[x * 3u + 0]; // R
            }
        }
        return true;
    }

    return false;
}

void FrameConverter::ScaleAndCenterToScratchBgra(const ShmFrameSnapshot& src, uint32_t dstW, uint32_t dstH) {
    EnsureScratch(dstW, dstH);

    // Fill letterbox/pillarbox canvas with opaque black.
    uint8_t* dst = m_scratchBgra.data();
    const size_t totalPixels = static_cast<size_t>(dstW) * dstH;
    for (size_t i = 0; i < totalPixels; ++i) {
        dst[i * 4u + 0] = 0;
        dst[i * 4u + 1] = 0;
        dst[i * 4u + 2] = 0;
        dst[i * 4u + 3] = 255;
    }

    const double scaleX = static_cast<double>(dstW) / static_cast<double>(src.width);
    const double scaleY = static_cast<double>(dstH) / static_cast<double>(src.height);
    const double scale = (scaleX < scaleY) ? scaleX : scaleY;

    uint32_t fitW = static_cast<uint32_t>(std::lround(src.width * scale));
    uint32_t fitH = static_cast<uint32_t>(std::lround(src.height * scale));
    fitW = std::clamp(fitW & ~1u, 2u, dstW);
    fitH = std::clamp(fitH & ~1u, 2u, dstH);

    const uint32_t offX = ((dstW - fitW) / 2u) & ~1u;
    const uint32_t offY = ((dstH - fitH) / 2u) & ~1u;

    for (uint32_t dy = 0; dy < fitH; ++dy) {
        const uint32_t syFp = static_cast<uint32_t>((static_cast<uint64_t>(dy) * (src.height - 1u) << 8) /
                                                    std::max(1u, fitH - 1u));
        const uint32_t sy0 = std::min(src.height - 1u, syFp >> 8);
        const uint32_t sy1 = std::min(src.height - 1u, sy0 + 1u);
        const uint32_t wy1 = syFp & 0xFFu;
        const uint32_t wy0 = 256u - wy1;

        uint8_t* dstRow = dst + (static_cast<size_t>(offY + dy) * dstW + offX) * 4u;

        for (uint32_t dx = 0; dx < fitW; ++dx) {
            const uint32_t sxFp = static_cast<uint32_t>((static_cast<uint64_t>(dx) * (src.width - 1u) << 8) /
                                                        std::max(1u, fitW - 1u));
            const uint32_t sx0 = std::min(src.width - 1u, sxFp >> 8);
            const uint32_t sx1 = std::min(src.width - 1u, sx0 + 1u);
            const uint32_t wx1 = sxFp & 0xFFu;
            const uint32_t wx0 = 256u - wx1;

            uint8_t b00, g00, r00, b01, g01, r01, b10, g10, r10, b11, g11, r11;
            FetchSrcPixelBgra(src, sx0, sy0, b00, g00, r00);
            FetchSrcPixelBgra(src, sx1, sy0, b01, g01, r01);
            FetchSrcPixelBgra(src, sx0, sy1, b10, g10, r10);
            FetchSrcPixelBgra(src, sx1, sy1, b11, g11, r11);

            const uint32_t w00 = wx0 * wy0;
            const uint32_t w01 = wx1 * wy0;
            const uint32_t w10 = wx0 * wy1;
            const uint32_t w11 = wx1 * wy1;

            dstRow[dx * 4u + 0] = static_cast<uint8_t>((b00 * w00 + b01 * w01 + b10 * w10 + b11 * w11 + 32768u) >> 16);
            dstRow[dx * 4u + 1] = static_cast<uint8_t>((g00 * w00 + g01 * w01 + g10 * w10 + g11 * w11 + 32768u) >> 16);
            dstRow[dx * 4u + 2] = static_cast<uint8_t>((r00 * w00 + r01 * w01 + r10 * w10 + r11 * w11 + 32768u) >> 16);
            dstRow[dx * 4u + 3] = 255;
        }
    }
}

bool FrameConverter::ConvertLiveFrame(
    const ShmFrameSnapshot& src,
    const TargetFormatDesc& dst,
    uint8_t* dstBuf,
    size_t dstBufBytes) {
    if (src.data == nullptr || src.width == 0 || src.height == 0) {
        return false;
    }

    const uint8_t flipMask = src.flipFlags & (phonelink_cam::FlipFlags::kHorizontal | phonelink_cam::FlipFlags::kVertical);
    const bool needsFlip = (flipMask != 0) && ((src.flipFlags & phonelink_cam::FlipFlags::kPreApplied) == 0);

    if (!needsFlip && src.width == dst.width && src.height == dst.height) {
        if (src.format == phonelink_cam::PixelFormat::BGRA32) {
            return ConvertBgraToTarget(src.data, src.stride, dst, dstBuf, dstBufBytes);
        }
        if (src.format == phonelink_cam::PixelFormat::NV12) {
            return ConvertNv12ToTarget1to1(src, dst, dstBuf, dstBufBytes);
        }
        if (src.format == phonelink_cam::PixelFormat::RGB24 &&
            (dst.format == TargetFormat::RGB32 || dst.format == TargetFormat::RGB24)) {
            return ConvertRgb24ToTarget1to1(src, dst, dstBuf, dstBufBytes);
        }
    }

    ScaleAndCenterToScratchBgra(src, dst.width, dst.height);
    if (needsFlip) {
        FlipBgraBufferInPlace(
            m_scratchBgra.data(),
            dst.width,
            dst.height,
            (flipMask & phonelink_cam::FlipFlags::kHorizontal) != 0,
            (flipMask & phonelink_cam::FlipFlags::kVertical) != 0);
    }
    return ConvertBgraToTarget(m_scratchBgra.data(), dst.width * 4u, dst, dstBuf, dstBufBytes);
}

bool FrameConverter::RenderStandby(
    const TargetFormatDesc& dst,
    uint64_t frameIndex,
    bool isPaused,
    uint8_t flipFlags,
    uint8_t* dstBuf,
    size_t dstBufBytes) {
    EnsureScratch(dst.width, dst.height);

    const uint32_t w = dst.width;
    const uint32_t h = dst.height;
    uint8_t* buf = m_scratchBgra.data();

    // Animated diagonal sweep position (cycles every 120 frames = 4 seconds at 30fps).
    const int sweepX = static_cast<int>((frameIndex * 14u) % (w + h));

    for (uint32_t y = 0; y < h; ++y) {
        const int baseR = 14 + static_cast<int>((y * 14u) / std::max(1u, h));
        const int baseG = 22 + static_cast<int>((y * 22u) / std::max(1u, h));
        const int baseB = 38 + static_cast<int>((y * 34u) / std::max(1u, h));
        uint8_t* row = buf + static_cast<size_t>(y) * w * 4u;

        for (uint32_t x = 0; x < w; ++x) {
            const int diag = static_cast<int>(x + y / 2u);
            const int dist = std::abs(diag - sweepX);
            int boost = 0;
            if (dist < 48) {
                boost = (48 - dist) / 3;
            }
            row[x * 4u + 0] = ClampU8(baseB + boost * 2);
            row[x * 4u + 1] = ClampU8(baseG + boost);
            row[x * 4u + 2] = ClampU8(baseR + (isPaused ? boost : boost / 3));
            row[x * 4u + 3] = 255;
        }
    }

    // Center dark card border.
    const int cardW = static_cast<int>(w * 3u / 4u);
    const int cardH = static_cast<int>(h / 2u);
    const int cardX = (static_cast<int>(w) - cardW) / 2;
    const int cardY = (static_cast<int>(h) - cardH) / 2;

    const uint8_t accentB = isPaused ? 30 : 248;
    const uint8_t accentG = isPaused ? 170 : 190;
    const uint8_t accentR = isPaused ? 245 : 56;

    FillRectBgra(buf, w, h, cardX, cardY, cardW, 3, accentB, accentG, accentR);
    FillRectBgra(buf, w, h, cardX, cardY + cardH - 3, cardW, 3, accentB, accentG, accentR);
    FillRectBgra(buf, w, h, cardX, cardY, 3, cardH, accentB, accentG, accentR);
    FillRectBgra(buf, w, h, cardX + cardW - 3, cardY, 3, cardH, accentB, accentG, accentR);

    // Corner alignment brackets.
    const int cornerLen = std::max(16, static_cast<int>(w / 32u));
    FillRectBgra(buf, w, h, 20, 20, cornerLen, 3, accentB, accentG, accentR);
    FillRectBgra(buf, w, h, 20, 20, 3, cornerLen, accentB, accentG, accentR);
    FillRectBgra(buf, w, h, static_cast<int>(w) - 20 - cornerLen, 20, cornerLen, 3, accentB, accentG, accentR);
    FillRectBgra(buf, w, h, static_cast<int>(w) - 23, 20, 3, cornerLen, accentB, accentG, accentR);

    // Animated bouncing progress bar inside card.
    const int barTrackW = cardW - 80;
    const int barX = cardX + 40;
    const int barY = cardY + cardH - 36;
    const int knobW = std::max(36, barTrackW / 6);
    const int travel = std::max(1, barTrackW - knobW);
    const uint64_t cycle = frameIndex % 90u;
    const int pos = (cycle < 45u)
        ? static_cast<int>((cycle * travel) / 45u)
        : static_cast<int>(((90u - cycle) * travel) / 45u);
    FillRectBgra(buf, w, h, barX, barY, barTrackW, 8, 60, 48, 36);
    FillRectBgra(buf, w, h, barX + pos, barY, knobW, 8, accentB, accentG, accentR);

    const int textScale = std::max(2, static_cast<int>(w / 320u));
    DrawTextCenteredBgra(buf, w, h, "WIN10 PHONELINK VIRTUAL CAM", cardY + cardH / 4, textScale, 250, 245, 240);
    DrawTextCenteredBgra(
        buf,
        w,
        h,
        isPaused ? "STREAM PAUSED" : "WAITING FOR PHONE STREAM...",
        cardY + cardH / 2,
        textScale,
        accentB,
        accentG,
        accentR);
    DrawTextCenteredBgra(
        buf,
        w,
        h,
        isPaused ? "RESUME IN PHONECAMRECEIVER" : "START PHONECAMRECEIVER & SCAN QR",
        cardY + (cardH * 3) / 4 - 16,
        std::max(2, textScale - 1),
        200,
        190,
        180);

    const bool userFlipH = (flipFlags & phonelink_cam::FlipFlags::kHorizontal) != 0;
    const bool userFlipV = (flipFlags & phonelink_cam::FlipFlags::kVertical) != 0;
    // Video call apps (Discord/Zoom/Teams/Meet) horizontally mirror local camera previews by default,
    // so standby text is pre-mirrored horizontally when userFlipH is false.
    FlipBgraBufferInPlace(buf, w, h, !userFlipH, userFlipV);

    return ConvertBgraToTarget(buf, w * 4u, dst, dstBuf, dstBufBytes);
}

} // namespace vcam
