#pragma once

#include <cstddef>
#include <cstdint>

namespace phonelink_cam {

inline constexpr wchar_t kShmMappingName[] = L"Local\\PhoneLinkCam_Shm";
inline constexpr wchar_t kShmEventName[] = L"Local\\PhoneLinkCam_Event";

inline constexpr uint32_t kShmMagic = 0x504C434Du; // 'PLCM'
inline constexpr uint32_t kShmVersion = 1u;

inline constexpr uint32_t kMaxFrameWidth = 1920u;
inline constexpr uint32_t kMaxFrameHeight = 1080u;
inline constexpr uint32_t kMaxBytesPerPixel = 4u;
inline constexpr uint32_t kShmSlotCount = 3u;

inline constexpr size_t kShmHeaderSize = 64u;
inline constexpr size_t kShmSlotSize =
    static_cast<size_t>(kMaxFrameWidth) * kMaxFrameHeight * kMaxBytesPerPixel; // 8,294,400 bytes
inline constexpr size_t kShmTotalSize =
    kShmHeaderSize + kShmSlotCount * kShmSlotSize; // 24,883,264 bytes

enum class FrameState : uint32_t {
    Disconnected = 0,
    Live = 1,
    Paused = 2,
};

enum class PixelFormat : uint32_t {
    Unknown = 0,
    NV12 = 1,   // Y plane (stride * height) + interleaved UV plane (stride * height / 2)
    BGRA32 = 2, // Top-down packed [B, G, R, A], 4 bytes per pixel
    RGB24 = 3,  // Top-down packed [R, G, B], 3 bytes per pixel
};

namespace FlipFlags {
inline constexpr uint8_t kNone = 0x00u;
inline constexpr uint8_t kHorizontal = 0x01u; // Mirror left/right
inline constexpr uint8_t kVertical = 0x02u;   // Flip top/bottom
inline constexpr uint8_t kPreApplied = 0x80u; // Producer already flipped live frame pixels in SHM
} // namespace FlipFlags

namespace OrientationHint {
inline constexpr uint8_t kUnspecified = 0x00u;
inline constexpr uint8_t kLandscape = 0x01u;
inline constexpr uint8_t kPortrait = 0x02u;
} // namespace OrientationHint

inline constexpr uint32_t kMaxFrameDimension =
    (kMaxFrameWidth > kMaxFrameHeight) ? kMaxFrameWidth : kMaxFrameHeight;

#pragma pack(push, 1)
struct ShmHeader {
    uint32_t magic;          // Offset 0: 0x504C434D ('PLCM')
    uint32_t version;        // Offset 4: 1
    uint64_t write_seq;      // Offset 8: monotonically incremented after each frame write
    uint32_t active_slot;    // Offset 16: 0, 1, or 2
    uint32_t frame_state;    // Offset 20: 0 = DISCONNECTED, 1 = LIVE, 2 = PAUSED
    uint32_t width;          // Offset 24: frame width in pixels
    uint32_t height;         // Offset 28: frame height in pixels
    uint32_t pixel_format;   // Offset 32: 1 = NV12, 2 = BGRA32, 3 = RGB24
    uint32_t stride;         // Offset 36: bytes per row of primary plane
    int64_t timestamp_100ns; // Offset 40: frame timestamp in 100ns units
    uint32_t payload_size;   // Offset 48: valid frame byte count inside active slot
    uint8_t reserved[12];    // Offset 52: zero-filled reserved bytes
};
#pragma pack(pop)

static_assert(sizeof(ShmHeader) == kShmHeaderSize, "ShmHeader must be 64 bytes");
static_assert(offsetof(ShmHeader, magic) == 0, "magic offset mismatch");
static_assert(offsetof(ShmHeader, version) == 4, "version offset mismatch");
static_assert(offsetof(ShmHeader, write_seq) == 8, "write_seq offset mismatch");
static_assert(offsetof(ShmHeader, active_slot) == 16, "active_slot offset mismatch");
static_assert(offsetof(ShmHeader, frame_state) == 20, "frame_state offset mismatch");
static_assert(offsetof(ShmHeader, width) == 24, "width offset mismatch");
static_assert(offsetof(ShmHeader, height) == 28, "height offset mismatch");
static_assert(offsetof(ShmHeader, pixel_format) == 32, "pixel_format offset mismatch");
static_assert(offsetof(ShmHeader, stride) == 36, "stride offset mismatch");
static_assert(offsetof(ShmHeader, timestamp_100ns) == 40, "timestamp_100ns offset mismatch");
static_assert(offsetof(ShmHeader, payload_size) == 48, "payload_size offset mismatch");
static_assert(offsetof(ShmHeader, reserved) == 52, "reserved offset mismatch");
static_assert(kShmSlotSize == 8294400u, "kShmSlotSize must be 8,294,400");
static_assert(kShmTotalSize == 24883264u, "kShmTotalSize must be 24,883,264");

inline constexpr size_t ShmSlotOffset(uint32_t slotIndex) noexcept {
    return kShmHeaderSize + static_cast<size_t>(slotIndex) * kShmSlotSize;
}

} // namespace phonelink_cam
