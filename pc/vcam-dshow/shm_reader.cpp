#include "shm_reader.h"

#include <atomic>
#include <cstring>

namespace vcam {

namespace {

template <typename T>
inline T VolatileLoad(const volatile T* ptr) noexcept {
    return *ptr;
}

inline void ReadHeaderVolatile(const uint8_t* base, phonelink_cam::ShmHeader& out) noexcept {
    const auto* v = reinterpret_cast<const volatile phonelink_cam::ShmHeader*>(base);
    out.magic = VolatileLoad(&v->magic);
    out.version = VolatileLoad(&v->version);
    out.write_seq = VolatileLoad(&v->write_seq);
    out.active_slot = VolatileLoad(&v->active_slot);
    out.frame_state = VolatileLoad(&v->frame_state);
    out.width = VolatileLoad(&v->width);
    out.height = VolatileLoad(&v->height);
    out.pixel_format = VolatileLoad(&v->pixel_format);
    out.stride = VolatileLoad(&v->stride);
    out.timestamp_100ns = VolatileLoad(&v->timestamp_100ns);
    out.payload_size = VolatileLoad(&v->payload_size);
}

} // namespace

ShmReader::~ShmReader() {
    Close();
}

void ShmReader::Close() {
    if (m_view != nullptr) {
        UnmapViewOfFile(m_view);
        m_view = nullptr;
    }
    if (m_hMap != nullptr) {
        CloseHandle(m_hMap);
        m_hMap = nullptr;
    }
    m_viewSize = 0;
    m_sawEvent = false;
    m_lastSeq = 0;
    m_staleTicks = 0;
}

bool ShmReader::EnsureOpen() {
    if (m_view != nullptr) {
        return true;
    }
    if (m_reopenCooldown > 0) {
        --m_reopenCooldown;
        return false;
    }

    HANDLE hMap = OpenFileMappingW(FILE_MAP_READ, FALSE, phonelink_cam::kShmMappingName);
    if (hMap == nullptr) {
        m_reopenCooldown = 3; // ~100 ms at 30 fps
        return false;
    }

    const void* view = MapViewOfFile(hMap, FILE_MAP_READ, 0, 0, phonelink_cam::kShmTotalSize);
    if (view == nullptr) {
        CloseHandle(hMap);
        m_reopenCooldown = 3;
        return false;
    }

    MEMORY_BASIC_INFORMATION mbi{};
    if (VirtualQuery(view, &mbi, sizeof(mbi)) == 0 || mbi.RegionSize < phonelink_cam::kShmTotalSize) {
        UnmapViewOfFile(view);
        CloseHandle(hMap);
        m_reopenCooldown = 3;
        return false;
    }

    m_hMap = hMap;
    m_view = static_cast<const uint8_t*>(view);
    m_viewSize = mbi.RegionSize;
    m_sawEvent = false;
    m_lastSeq = 0;
    m_staleTicks = 0;

    HANDLE hEvent = OpenEventW(SYNCHRONIZE, FALSE, phonelink_cam::kShmEventName);
    if (hEvent != nullptr) {
        m_sawEvent = true;
        CloseHandle(hEvent);
    }

    return true;
}

bool ShmReader::ComputeLayout(
    const phonelink_cam::ShmHeader& hdr,
    uint32_t& outStride,
    size_t& outRequiredBytes) {
    // Allow both landscape (up to 1920x1080) and portrait (e.g. 1080x1920 or 720x1280)
    // as long as the frame fits within the 8,294,400-byte slot.
    if (hdr.width == 0 || hdr.height == 0 ||
        hdr.width > phonelink_cam::kMaxFrameWidth ||
        hdr.height > phonelink_cam::kMaxFrameWidth) {
        return false;
    }

    const auto fmt = static_cast<phonelink_cam::PixelFormat>(hdr.pixel_format);
    size_t minStride = 0;
    size_t required = 0;

    switch (fmt) {
    case phonelink_cam::PixelFormat::NV12:
        if ((hdr.width & 1u) != 0 || (hdr.height & 1u) != 0) {
            return false;
        }
        minStride = hdr.width;
        outStride = (hdr.stride >= minStride) ? hdr.stride : static_cast<uint32_t>(minStride);
        required = static_cast<size_t>(outStride) * hdr.height +
                   static_cast<size_t>(outStride) * (hdr.height / 2u);
        break;

    case phonelink_cam::PixelFormat::BGRA32:
        minStride = static_cast<size_t>(hdr.width) * 4u;
        outStride = (hdr.stride >= minStride) ? hdr.stride : static_cast<uint32_t>(minStride);
        required = static_cast<size_t>(outStride) * hdr.height;
        break;

    case phonelink_cam::PixelFormat::RGB24:
        minStride = static_cast<size_t>(hdr.width) * 3u;
        outStride = (hdr.stride >= minStride) ? hdr.stride : static_cast<uint32_t>(minStride);
        required = static_cast<size_t>(outStride) * hdr.height;
        break;

    default:
        return false;
    }

    if (required == 0 || required > phonelink_cam::kShmSlotSize) {
        return false;
    }
    if (hdr.payload_size != 0 && hdr.payload_size < required) {
        return false;
    }

    outRequiredBytes = required;
    return true;
}

ShmReadStatus ShmReader::ReadLatest(ShmFrameSnapshot& outSnapshot) {
    outSnapshot = {};

    if (!EnsureOpen()) {
        return ShmReadStatus::Disconnected;
    }

    // Track whether producer event exists so we can detect abrupt producer termination.
    if (!m_sawEvent) {
        HANDLE hEvent = OpenEventW(SYNCHRONIZE, FALSE, phonelink_cam::kShmEventName);
        if (hEvent != nullptr) {
            m_sawEvent = true;
            CloseHandle(hEvent);
        }
    }

    for (int attempt = 0; attempt < 3; ++attempt) {
        const uint64_t seq1 = VolatileLoad(
            reinterpret_cast<const volatile uint64_t*>(m_view + offsetof(phonelink_cam::ShmHeader, write_seq)));
        std::atomic_thread_fence(std::memory_order_acquire);

        phonelink_cam::ShmHeader hdr{};
        ReadHeaderVolatile(m_view, hdr);

        if (hdr.magic != phonelink_cam::kShmMagic || hdr.version != phonelink_cam::kShmVersion) {
            Close();
            m_reopenCooldown = 3;
            return ShmReadStatus::Disconnected;
        }

        const auto state = static_cast<phonelink_cam::FrameState>(hdr.frame_state);
        if (state == phonelink_cam::FrameState::Disconnected) {
            Close();
            m_reopenCooldown = 3;
            return ShmReadStatus::Disconnected;
        }
        if (state == phonelink_cam::FrameState::Paused) {
            outSnapshot.status = ShmReadStatus::Paused;
            return ShmReadStatus::Paused;
        }
        if (state != phonelink_cam::FrameState::Live || hdr.write_seq == 0) {
            return ShmReadStatus::Disconnected;
        }
        if (hdr.active_slot >= phonelink_cam::kShmSlotCount) {
            return ShmReadStatus::Disconnected;
        }

        uint32_t effectiveStride = 0;
        size_t requiredBytes = 0;
        if (!ComputeLayout(hdr, effectiveStride, requiredBytes)) {
            return ShmReadStatus::Disconnected;
        }

        const size_t slotOffset = phonelink_cam::ShmSlotOffset(hdr.active_slot);
        if (slotOffset + requiredBytes > m_viewSize) {
            return ShmReadStatus::Disconnected;
        }

        if (m_copyBuffer.size() < requiredBytes) {
            m_copyBuffer.resize(requiredBytes);
        }
        std::memcpy(m_copyBuffer.data(), m_view + slotOffset, requiredBytes);

        std::atomic_thread_fence(std::memory_order_acquire);
        const uint64_t seq2 = VolatileLoad(
            reinterpret_cast<const volatile uint64_t*>(m_view + offsetof(phonelink_cam::ShmHeader, write_seq)));

        if (seq1 == seq2 && seq2 == hdr.write_seq) {
            if (seq2 == m_lastSeq) {
                ++m_staleTicks;
                if (m_sawEvent && m_staleTicks >= 45) {
                    HANDLE hEvent = OpenEventW(SYNCHRONIZE, FALSE, phonelink_cam::kShmEventName);
                    if (hEvent == nullptr) {
                        // Producer process exited without clearing frame_state.
                        Close();
                        m_reopenCooldown = 3;
                        return ShmReadStatus::Disconnected;
                    }
                    CloseHandle(hEvent);
                    m_staleTicks = 0;
                }
            } else {
                m_lastSeq = seq2;
                m_staleTicks = 0;
            }

            outSnapshot.status = ShmReadStatus::Live;
            outSnapshot.width = hdr.width;
            outSnapshot.height = hdr.height;
            outSnapshot.format = static_cast<phonelink_cam::PixelFormat>(hdr.pixel_format);
            outSnapshot.stride = effectiveStride;
            outSnapshot.timestamp100ns = hdr.timestamp_100ns;
            outSnapshot.writeSeq = seq2;
            outSnapshot.data = m_copyBuffer.data();
            outSnapshot.dataSize = requiredBytes;
            return ShmReadStatus::Live;
        }
    }

    // If producer continuously raced all 3 reads (unlikely at 30fps), keep fallback safe.
    return ShmReadStatus::Disconnected;
}

} // namespace vcam
