#include "vcam_filter.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
#include <utility>

namespace vcam {

namespace {

constexpr REFERENCE_TIME kDefaultFrameInterval = 166666; // 60 fps in 100ns units (10,000,000 / 166666 == 60)

struct ResPreset {
    uint32_t w;
    uint32_t h;
};

constexpr ResPreset kLandscapePresets[] = {
    { 1280, 720  },
    { 1920, 1080 },
    { 960,  540  },
    { 640,  360  },
    { 640,  480  },
};

constexpr ResPreset kPortraitPresets[] = {
    { 720,  1280 },
    { 1080, 1920 },
    { 540,  960  },
    { 360,  640  },
};

void FreeMediaTypeFields(AM_MEDIA_TYPE& mt) noexcept {
    if (mt.cbFormat != 0 && mt.pbFormat != nullptr) {
        CoTaskMemFree(mt.pbFormat);
        mt.pbFormat = nullptr;
        mt.cbFormat = 0;
    }
    if (mt.pUnk != nullptr) {
        mt.pUnk->Release();
        mt.pUnk = nullptr;
    }
}

void DeleteCoTaskMediaType(AM_MEDIA_TYPE* pmt) noexcept {
    if (pmt != nullptr) {
        FreeMediaTypeFields(*pmt);
        CoTaskMemFree(pmt);
    }
}

HRESULT CopyMediaTypeFields(AM_MEDIA_TYPE& dst, const AM_MEDIA_TYPE& src) noexcept {
    FreeMediaTypeFields(dst);
    dst = src;
    dst.pbFormat = nullptr;
    dst.cbFormat = 0;
    dst.pUnk = nullptr;

    if (src.cbFormat > 0 && src.pbFormat != nullptr) {
        dst.pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(src.cbFormat));
        if (dst.pbFormat == nullptr) {
            return E_OUTOFMEMORY;
        }
        std::memcpy(dst.pbFormat, src.pbFormat, src.cbFormat);
        dst.cbFormat = src.cbFormat;
    }
    if (src.pUnk != nullptr) {
        dst.pUnk = src.pUnk;
        dst.pUnk->AddRef();
    }
    return S_OK;
}

AM_MEDIA_TYPE* CloneMediaTypeAlloc(const AM_MEDIA_TYPE* src) noexcept {
    if (src == nullptr) {
        return nullptr;
    }
    auto* copy = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (copy == nullptr) {
        return nullptr;
    }
    std::memset(copy, 0, sizeof(AM_MEDIA_TYPE));
    if (FAILED(CopyMediaTypeFields(*copy, *src))) {
        CoTaskMemFree(copy);
        return nullptr;
    }
    return copy;
}

const GUID& SubtypeForTarget(TargetFormat fmt) noexcept {
    switch (fmt) {
    case TargetFormat::NV12:  return MEDIASUBTYPE_NV12;
    case TargetFormat::YUY2:  return MEDIASUBTYPE_YUY2;
    case TargetFormat::RGB32: return MEDIASUBTYPE_RGB32;
    case TargetFormat::RGB24: return MEDIASUBTYPE_RGB24;
    default:                  return MEDIASUBTYPE_NV12;
    }
}

bool TargetFromSubtype(const GUID& subtype, TargetFormat& outFmt) noexcept {
    if (subtype == MEDIASUBTYPE_NV12)  { outFmt = TargetFormat::NV12;  return true; }
    if (subtype == MEDIASUBTYPE_YUY2)  { outFmt = TargetFormat::YUY2;  return true; }
    if (subtype == MEDIASUBTYPE_RGB32) { outFmt = TargetFormat::RGB32; return true; }
    if (subtype == MEDIASUBTYPE_RGB24) { outFmt = TargetFormat::RGB24; return true; }
    return false;
}

uint32_t StrideForFormat(TargetFormat fmt, uint32_t width) noexcept {
    switch (fmt) {
    case TargetFormat::NV12:  return width;
    case TargetFormat::YUY2:  return width * 2u;
    case TargetFormat::RGB32: return width * 4u;
    case TargetFormat::RGB24: return ((width * 3u + 3u) & ~3u);
    default:                  return width;
    }
}

uint32_t ImageSizeForFormat(TargetFormat fmt, uint32_t width, uint32_t height) noexcept {
    const uint32_t stride = StrideForFormat(fmt, width);
    if (fmt == TargetFormat::NV12) {
        return stride * height + stride * (height / 2u);
    }
    return stride * height;
}

WORD BitCountForFormat(TargetFormat fmt) noexcept {
    switch (fmt) {
    case TargetFormat::NV12:  return 12;
    case TargetFormat::YUY2:  return 16;
    case TargetFormat::RGB32: return 32;
    case TargetFormat::RGB24: return 24;
    default:                  return 12;
    }
}

DWORD CompressionForFormat(TargetFormat fmt) noexcept {
    switch (fmt) {
    case TargetFormat::NV12:  return MAKEFOURCC('N', 'V', '1', '2');
    case TargetFormat::YUY2:  return MAKEFOURCC('Y', 'U', 'Y', '2');
    case TargetFormat::RGB32: return BI_RGB;
    case TargetFormat::RGB24: return BI_RGB;
    default:                  return BI_RGB;
    }
}

class CEnumPins final : public IEnumPins {
public:
    explicit CEnumPins(IPin* pin, ULONG startIndex = 0) : m_index(startIndex) {
        DllAddRef();
        if (pin != nullptr) {
            pin->AddRef();
            m_pin = pin;
        }
    }
    ~CEnumPins() {
        if (m_pin != nullptr) {
            m_pin->Release();
        }
        DllRelease();
    }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IEnumPins) {
            *ppv = static_cast<IEnumPins*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(m_refCount.fetch_add(1, std::memory_order_relaxed) + 1);
    }

    ULONG STDMETHODCALLTYPE Release() override {
        const long c = m_refCount.fetch_sub(1, std::memory_order_acq_rel) - 1;
        if (c == 0) {
            delete this;
        }
        return static_cast<ULONG>(c);
    }

    HRESULT STDMETHODCALLTYPE Next(ULONG cPins, IPin** ppPins, ULONG* pcFetched) override {
        if (ppPins == nullptr) return E_POINTER;
        if (cPins > 1 && pcFetched == nullptr) return E_INVALIDARG;

        ULONG fetched = 0;
        if (cPins > 0 && m_index == 0 && m_pin != nullptr) {
            m_pin->AddRef();
            ppPins[0] = m_pin;
            fetched = 1;
            m_index = 1;
        }
        if (pcFetched != nullptr) {
            *pcFetched = fetched;
        }
        return (fetched == cPins) ? S_OK : S_FALSE;
    }

    HRESULT STDMETHODCALLTYPE Skip(ULONG cPins) override {
        m_index = std::min<ULONG>(1u, m_index + cPins);
        return (m_index < 1u || cPins == 0) ? S_OK : S_FALSE;
    }

    HRESULT STDMETHODCALLTYPE Reset() override {
        m_index = 0;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE Clone(IEnumPins** ppEnum) override {
        if (ppEnum == nullptr) return E_POINTER;
        auto* clone = new (std::nothrow) CEnumPins(m_pin, m_index);
        if (clone == nullptr) return E_OUTOFMEMORY;
        *ppEnum = clone;
        return S_OK;
    }

private:
    std::atomic<long> m_refCount{1};
    IPin* m_pin = nullptr;
    ULONG m_index = 0;
};

class CEnumMediaTypes final : public IEnumMediaTypes {
public:
    CEnumMediaTypes(std::vector<AM_MEDIA_TYPE*> types, size_t index = 0)
        : m_types(std::move(types)), m_index(index) {
        DllAddRef();
    }

    ~CEnumMediaTypes() {
        for (AM_MEDIA_TYPE* pmt : m_types) {
            DeleteCoTaskMediaType(pmt);
        }
        DllRelease();
    }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IEnumMediaTypes) {
            *ppv = static_cast<IEnumMediaTypes*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(m_refCount.fetch_add(1, std::memory_order_relaxed) + 1);
    }

    ULONG STDMETHODCALLTYPE Release() override {
        const long c = m_refCount.fetch_sub(1, std::memory_order_acq_rel) - 1;
        if (c == 0) {
            delete this;
        }
        return static_cast<ULONG>(c);
    }

    HRESULT STDMETHODCALLTYPE Next(ULONG cMediaTypes, AM_MEDIA_TYPE** ppMediaTypes, ULONG* pcFetched) override {
        if (ppMediaTypes == nullptr) return E_POINTER;
        if (cMediaTypes > 1 && pcFetched == nullptr) return E_INVALIDARG;

        ULONG fetched = 0;
        while (fetched < cMediaTypes && m_index < m_types.size()) {
            AM_MEDIA_TYPE* copy = CloneMediaTypeAlloc(m_types[m_index]);
            if (copy == nullptr) {
                for (ULONG i = 0; i < fetched; ++i) {
                    DeleteCoTaskMediaType(ppMediaTypes[i]);
                    ppMediaTypes[i] = nullptr;
                }
                return E_OUTOFMEMORY;
            }
            ppMediaTypes[fetched] = copy;
            ++fetched;
            ++m_index;
        }

        if (pcFetched != nullptr) {
            *pcFetched = fetched;
        }
        return (fetched == cMediaTypes) ? S_OK : S_FALSE;
    }

    HRESULT STDMETHODCALLTYPE Skip(ULONG cMediaTypes) override {
        const size_t remaining = (m_index < m_types.size()) ? (m_types.size() - m_index) : 0;
        if (cMediaTypes <= remaining) {
            m_index += cMediaTypes;
            return S_OK;
        }
        m_index = m_types.size();
        return S_FALSE;
    }

    HRESULT STDMETHODCALLTYPE Reset() override {
        m_index = 0;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE Clone(IEnumMediaTypes** ppEnum) override {
        if (ppEnum == nullptr) return E_POINTER;
        std::vector<AM_MEDIA_TYPE*> copies;
        copies.reserve(m_types.size());
        for (const AM_MEDIA_TYPE* mt : m_types) {
            AM_MEDIA_TYPE* c = CloneMediaTypeAlloc(mt);
            if (c == nullptr) {
                for (AM_MEDIA_TYPE* p : copies) DeleteCoTaskMediaType(p);
                return E_OUTOFMEMORY;
            }
            copies.push_back(c);
        }
        auto* clone = new (std::nothrow) CEnumMediaTypes(std::move(copies), m_index);
        if (clone == nullptr) {
            for (AM_MEDIA_TYPE* p : copies) DeleteCoTaskMediaType(p);
            return E_OUTOFMEMORY;
        }
        *ppEnum = clone;
        return S_OK;
    }

private:
    std::atomic<long> m_refCount{1};
    std::vector<AM_MEDIA_TYPE*> m_types;
    size_t m_index = 0;
};

} // namespace

// ============================================================================
// CVcamOutputPin
// ============================================================================

CVcamOutputPin::CVcamOutputPin(CVcamFilter* filter) : m_filter(filter) {
    m_stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    m_runEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    m_adviseEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
}

CVcamOutputPin::~CVcamOutputPin() {
    OnStopStreaming();
    FreeMediaTypeFields(m_connectedMt);
    FreeMediaTypeFields(m_preferredMt);
    if (m_stopEvent != nullptr) CloseHandle(m_stopEvent);
    if (m_runEvent != nullptr) CloseHandle(m_runEvent);
    if (m_adviseEvent != nullptr) CloseHandle(m_adviseEvent);
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::QueryInterface(REFIID riid, void** ppv) {
    if (ppv == nullptr) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IPin) {
        *ppv = static_cast<IPin*>(this);
    } else if (riid == IID_IAMStreamConfig) {
        *ppv = static_cast<IAMStreamConfig*>(this);
    } else if (riid == IID_IKsPropertySet) {
        *ppv = static_cast<IKsPropertySet*>(this);
    } else if (riid == IID_IQualityControl) {
        *ppv = static_cast<IQualityControl*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

ULONG STDMETHODCALLTYPE CVcamOutputPin::AddRef() {
    return m_filter->AddRef();
}

ULONG STDMETHODCALLTYPE CVcamOutputPin::Release() {
    return m_filter->Release();
}

AM_MEDIA_TYPE* CVcamOutputPin::CreateMediaTypeFromParams(
    TargetFormat fmt,
    uint32_t width,
    uint32_t height,
    bool bottomUp,
    REFERENCE_TIME interval) {
    auto* pmt = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (pmt == nullptr) return nullptr;
    auto* vih = static_cast<VIDEOINFOHEADER*>(CoTaskMemAlloc(sizeof(VIDEOINFOHEADER)));
    if (vih == nullptr) {
        CoTaskMemFree(pmt);
        return nullptr;
    }

    std::memset(pmt, 0, sizeof(AM_MEDIA_TYPE));
    std::memset(vih, 0, sizeof(VIDEOINFOHEADER));

    const uint32_t imgSize = ImageSizeForFormat(fmt, width, height);
    const bool isRgb = (fmt == TargetFormat::RGB32 || fmt == TargetFormat::RGB24);
    const LONG signedHeight = (isRgb && !bottomUp) ? -static_cast<LONG>(height) : static_cast<LONG>(height);

    vih->AvgTimePerFrame = interval > 0 ? interval : kDefaultFrameInterval;
    vih->dwBitRate = static_cast<DWORD>(static_cast<uint64_t>(imgSize) * 8u * 60u);
    vih->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    vih->bmiHeader.biWidth = static_cast<LONG>(width);
    vih->bmiHeader.biHeight = signedHeight;
    vih->bmiHeader.biPlanes = 1;
    vih->bmiHeader.biBitCount = BitCountForFormat(fmt);
    vih->bmiHeader.biCompression = CompressionForFormat(fmt);
    vih->bmiHeader.biSizeImage = imgSize;

    pmt->majortype = MEDIATYPE_Video;
    pmt->subtype = SubtypeForTarget(fmt);
    pmt->bFixedSizeSamples = TRUE;
    pmt->bTemporalCompression = FALSE;
    pmt->lSampleSize = static_cast<ULONG>(imgSize);
    pmt->formattype = FORMAT_VideoInfo;
    pmt->cbFormat = sizeof(VIDEOINFOHEADER);
    pmt->pbFormat = reinterpret_cast<BYTE*>(vih);
    return pmt;
}

AM_MEDIA_TYPE* CVcamOutputPin::CreateMediaTypeFromCap(const VideoCapEntry& cap) {
    const bool bottomUp = (cap.format == TargetFormat::RGB32 || cap.format == TargetFormat::RGB24);
    return CreateMediaTypeFromParams(cap.format, cap.width, cap.height, bottomUp, kDefaultFrameInterval);
}

std::vector<VideoCapEntry> CVcamOutputPin::BuildCapList() const {
    const ShmStreamHint hint = ShmReader::ProbeStreamHint();

    std::vector<VideoCapEntry> caps;
    std::vector<std::pair<uint32_t, uint32_t>> addedResolutions;

    auto addRes = [&](uint32_t w, uint32_t h) {
        if (w < 2u || h < 2u || (w & 1u) != 0 || (h & 1u) != 0) {
            return;
        }
        if (w > phonelink_cam::kMaxFrameDimension || h > phonelink_cam::kMaxFrameDimension) {
            return;
        }
        if (static_cast<uint64_t>(w) * static_cast<uint64_t>(h) * 4u > phonelink_cam::kShmSlotSize) {
            return;
        }
        for (const auto& existing : addedResolutions) {
            if (existing.first == w && existing.second == h) {
                return;
            }
        }
        addedResolutions.emplace_back(w, h);
        caps.push_back({ w, h, TargetFormat::NV12 });
        caps.push_back({ w, h, TargetFormat::YUY2 });
        caps.push_back({ w, h, TargetFormat::RGB32 });
        caps.push_back({ w, h, TargetFormat::RGB24 });
    };

    auto addPortraitSet = [&](uint32_t refW, uint32_t refH) {
        const double ratio = (refH > 0) ? static_cast<double>(refW) / static_cast<double>(refH) : (9.0 / 16.0);
        if (std::abs(ratio - (3.0 / 4.0)) < 0.03) {
            addRes(480, 640);
            addRes(720, 960);
            addRes(360, 480);
        } else if (std::abs(ratio - (9.0 / 16.0)) < 0.03) {
            for (const auto& p : kPortraitPresets) {
                addRes(p.w, p.h);
            }
        } else if (refW >= 720u && refH >= 1000u) {
            addRes((refW / 2u) & ~1u, (refH / 2u) & ~1u);
        }
    };

    auto addLandscapeSet = [&](uint32_t refW, uint32_t refH) {
        const double ratio = (refH > 0) ? static_cast<double>(refW) / static_cast<double>(refH) : (16.0 / 9.0);
        if (std::abs(ratio - (4.0 / 3.0)) < 0.03) {
            addRes(640, 480);
            addRes(960, 720);
            addRes(1440, 1080);
        } else {
            for (const auto& p : kLandscapePresets) {
                addRes(p.w, p.h);
            }
        }
    };

    if (hint.status == ShmReadStatus::Live && hint.width > 0 && hint.height > 0) {
        addRes(hint.width, hint.height);
        if (hint.height > hint.width) {
            addPortraitSet(hint.width, hint.height);
        } else {
            addLandscapeSet(hint.width, hint.height);
        }
    } else if (hint.orientationHint == phonelink_cam::OrientationHint::kLandscape) {
        if (hint.producerAlive && hint.prefWidth >= hint.prefHeight && hint.prefWidth > 0) {
            addRes(hint.prefWidth, hint.prefHeight);
            addLandscapeSet(hint.prefWidth, hint.prefHeight);
        } else {
            for (const auto& p : kLandscapePresets) {
                addRes(p.w, p.h);
            }
        }
    } else {
        // Portrait or unspecified (default phone camera orientation is portrait):
        // strictly advertise portrait-only caps so WebRTC/Discord and OBS never pick 1280x720 / 640x480.
        if (hint.producerAlive && hint.prefWidth > 0 && hint.prefHeight > hint.prefWidth) {
            addRes(hint.prefWidth, hint.prefHeight);
            addPortraitSet(hint.prefWidth, hint.prefHeight);
        } else {
            for (const auto& p : kPortraitPresets) {
                addRes(p.w, p.h);
            }
        }
    }

    if (caps.empty()) {
        addRes(720, 1280);
    }
    return caps;
}

bool CVcamOutputPin::PreferredMtMatchesCaps(const AM_MEDIA_TYPE& mt, const std::vector<VideoCapEntry>& caps) {
    if (caps.empty()) {
        return true;
    }
    TargetFormatDesc prefDesc{};
    REFERENCE_TIME dummy = 0;
    if (!ExtractTargetFormatDesc(&mt, prefDesc, dummy)) {
        return false;
    }
    const bool capPortrait = caps[0].height > caps[0].width;
    const bool capLandscape = caps[0].width > caps[0].height;
    if (capPortrait && prefDesc.width > prefDesc.height) {
        return false;
    }
    if (capLandscape && prefDesc.height > prefDesc.width) {
        return false;
    }
    const double capAspect = static_cast<double>(caps[0].width) / static_cast<double>(std::max(1u, caps[0].height));
    const double prefAspect = static_cast<double>(prefDesc.width) / static_cast<double>(std::max(1u, prefDesc.height));
    return std::abs(capAspect - prefAspect) < 0.06;
}

std::vector<AM_MEDIA_TYPE*> CVcamOutputPin::BuildMediaTypeList() const {
    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    const std::vector<VideoCapEntry> caps = BuildCapList();
    std::vector<AM_MEDIA_TYPE*> list;
    list.reserve(caps.size() + 1);

    if (m_hasPreferredMt && PreferredMtMatchesCaps(m_preferredMt, caps)) {
        if (AM_MEDIA_TYPE* pref = CloneMediaTypeAlloc(&m_preferredMt)) {
            list.push_back(pref);
        }
    }
    for (const auto& cap : caps) {
        if (AM_MEDIA_TYPE* pmt = CreateMediaTypeFromCap(cap)) {
            list.push_back(pmt);
        }
    }
    return list;
}

bool CVcamOutputPin::ExtractTargetFormatDesc(
    const AM_MEDIA_TYPE* pmt,
    TargetFormatDesc& outDesc,
    REFERENCE_TIME& outInterval) {
    if (pmt == nullptr || pmt->majortype != MEDIATYPE_Video) {
        return false;
    }

    TargetFormat fmt{};
    if (!TargetFromSubtype(pmt->subtype, fmt)) {
        return false;
    }

    const BITMAPINFOHEADER* bmi = nullptr;
    REFERENCE_TIME interval = kDefaultFrameInterval;

    if (pmt->formattype == FORMAT_VideoInfo &&
        pmt->pbFormat != nullptr &&
        pmt->cbFormat >= sizeof(VIDEOINFOHEADER)) {
        const auto* vih = reinterpret_cast<const VIDEOINFOHEADER*>(pmt->pbFormat);
        bmi = &vih->bmiHeader;
        if (vih->AvgTimePerFrame > 0) {
            interval = vih->AvgTimePerFrame;
        }
    } else if (pmt->formattype == FORMAT_VideoInfo2 &&
               pmt->pbFormat != nullptr &&
               pmt->cbFormat >= sizeof(VIDEOINFOHEADER2)) {
        const auto* vih2 = reinterpret_cast<const VIDEOINFOHEADER2*>(pmt->pbFormat);
        bmi = &vih2->bmiHeader;
        if (vih2->AvgTimePerFrame > 0) {
            interval = vih2->AvgTimePerFrame;
        }
    } else {
        return false;
    }

    if (bmi->biWidth <= 0 || bmi->biHeight == 0) {
        return false;
    }

    const uint32_t w = static_cast<uint32_t>(bmi->biWidth);
    const uint32_t h = static_cast<uint32_t>(std::abs(bmi->biHeight));
    if (w < 2u || h < 2u ||
        w > phonelink_cam::kMaxFrameDimension || h > phonelink_cam::kMaxFrameDimension ||
        static_cast<uint64_t>(w) * static_cast<uint64_t>(h) * 4u > phonelink_cam::kShmSlotSize) {
        return false;
    }
    if ((fmt == TargetFormat::NV12 || fmt == TargetFormat::YUY2) && ((w & 1u) != 0 || (h & 1u) != 0)) {
        return false;
    }

    outDesc.format = fmt;
    outDesc.width = w;
    outDesc.height = h;
    outDesc.stride = StrideForFormat(fmt, w);
    outDesc.bottomUp = (fmt == TargetFormat::RGB32 || fmt == TargetFormat::RGB24) && (bmi->biHeight > 0);
    outInterval = std::clamp<REFERENCE_TIME>(interval, 166666, 10000000);
    return true;
}

HRESULT CVcamOutputPin::CheckMediaType(const AM_MEDIA_TYPE* pmt) const {
    TargetFormatDesc desc{};
    REFERENCE_TIME interval = 0;
    return ExtractTargetFormatDesc(pmt, desc, interval) ? S_OK : VFW_E_TYPE_NOT_ACCEPTED;
}

bool CVcamOutputPin::MatchesPartialMediaType(const AM_MEDIA_TYPE* partial, const AM_MEDIA_TYPE* candidate) const {
    if (partial == nullptr) return true;
    if (partial->majortype != GUID_NULL && partial->majortype != candidate->majortype) return false;
    if (partial->subtype != GUID_NULL && partial->subtype != candidate->subtype) return false;
    if (partial->formattype != GUID_NULL && partial->formattype != candidate->formattype) return false;
    return true;
}

HRESULT CVcamOutputPin::AttemptConnect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) {
    TargetFormatDesc desc{};
    REFERENCE_TIME interval = 0;
    if (!ExtractTargetFormatDesc(pmt, desc, interval)) {
        return VFW_E_TYPE_NOT_ACCEPTED;
    }

    HRESULT hr = pReceivePin->ReceiveConnection(this, pmt);
    if (FAILED(hr)) {
        return hr;
    }

    ComPtr<IMemInputPin> inputPin;
    hr = pReceivePin->QueryInterface(IID_IMemInputPin, reinterpret_cast<void**>(inputPin.ReleaseAndGetAddressOf()));
    if (FAILED(hr) || !inputPin) {
        pReceivePin->Disconnect();
        return VFW_E_NO_TRANSPORT;
    }

    const size_t requiredBytes = FrameConverter::RequiredBufferSize(desc);
    ALLOCATOR_PROPERTIES reqProps{};
    inputPin->GetAllocatorRequirements(&reqProps);
    reqProps.cBuffers = std::max<long>(reqProps.cBuffers, 3);
    reqProps.cbBuffer = std::max<long>(reqProps.cbBuffer, static_cast<long>(requiredBytes));
    reqProps.cbAlign = std::max<long>(reqProps.cbAlign, 1);

    ComPtr<IMemAllocator> alloc;
    ALLOCATOR_PROPERTIES actualProps{};
    bool allocatorReady = false;

    if (SUCCEEDED(inputPin->GetAllocator(alloc.ReleaseAndGetAddressOf())) && alloc) {
        if (SUCCEEDED(alloc->SetProperties(&reqProps, &actualProps)) &&
            actualProps.cbBuffer >= static_cast<long>(requiredBytes)) {
            allocatorReady = true;
        }
    }

    if (!allocatorReady) {
        alloc.Reset();
        hr = CoCreateInstance(
            CLSID_MemoryAllocator,
            nullptr,
            CLSCTX_INPROC_SERVER,
            IID_IMemAllocator,
            reinterpret_cast<void**>(alloc.ReleaseAndGetAddressOf()));
        if (FAILED(hr) || !alloc) {
            pReceivePin->Disconnect();
            return FAILED(hr) ? hr : E_OUTOFMEMORY;
        }
        hr = alloc->SetProperties(&reqProps, &actualProps);
        if (FAILED(hr) || actualProps.cbBuffer < static_cast<long>(requiredBytes)) {
            pReceivePin->Disconnect();
            return FAILED(hr) ? hr : VFW_E_BADALIGN;
        }
    }

    hr = inputPin->NotifyAllocator(alloc.Get(), FALSE);
    if (FAILED(hr)) {
        pReceivePin->Disconnect();
        return hr;
    }

    hr = CopyMediaTypeFields(m_connectedMt, *pmt);
    if (FAILED(hr)) {
        pReceivePin->Disconnect();
        return hr;
    }

    m_hasConnectedMt = true;
    m_connectedPin.Reset();
    pReceivePin->AddRef();
    // Transfer ownership via copy/assignment:
    {
        ComPtr<IPin> tmp;
        *tmp.ReleaseAndGetAddressOf() = pReceivePin;
        m_connectedPin = tmp;
    }
    m_inputPin = inputPin;
    m_allocator = alloc;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) {
    if (pReceivePin == nullptr) return E_POINTER;
    if (m_filter->GetFilterState() != State_Stopped) return VFW_E_NOT_STOPPED;

    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    if (m_connectedPin) return VFW_E_ALREADY_CONNECTED;

    // Full media type supplied directly by graph builder / caller.
    if (pmt != nullptr && pmt->formattype != GUID_NULL && pmt->pbFormat != nullptr) {
        if (FAILED(CheckMediaType(pmt))) {
            return VFW_E_TYPE_NOT_ACCEPTED;
        }
        return AttemptConnect(pReceivePin, pmt);
    }

    // Partial or null template: enumerate supported formats in preference order.
    std::vector<AM_MEDIA_TYPE*> candidates = BuildMediaTypeList();
    HRESULT finalHr = VFW_E_NO_ACCEPTABLE_TYPES;

    for (AM_MEDIA_TYPE* cand : candidates) {
        if (MatchesPartialMediaType(pmt, cand)) {
            HRESULT hr = AttemptConnect(pReceivePin, cand);
            if (SUCCEEDED(hr)) {
                finalHr = S_OK;
                break;
            }
            finalHr = hr;
        }
    }

    for (AM_MEDIA_TYPE* cand : candidates) {
        DeleteCoTaskMediaType(cand);
    }
    return finalHr;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::ReceiveConnection(IPin*, const AM_MEDIA_TYPE*) {
    return E_UNEXPECTED;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::Disconnect() {
    if (m_filter->GetFilterState() != State_Stopped) return VFW_E_NOT_STOPPED;

    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    if (!m_connectedPin) {
        return S_FALSE;
    }
    m_allocator.Reset();
    m_inputPin.Reset();
    m_connectedPin.Reset();
    FreeMediaTypeFields(m_connectedMt);
    m_hasConnectedMt = false;
    FreeMediaTypeFields(m_preferredMt);
    m_hasPreferredMt = false;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::ConnectedTo(IPin** pPin) {
    if (pPin == nullptr) return E_POINTER;
    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    if (!m_connectedPin) {
        *pPin = nullptr;
        return VFW_E_NOT_CONNECTED;
    }
    *pPin = m_connectedPin.Get();
    (*pPin)->AddRef();
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::ConnectionMediaType(AM_MEDIA_TYPE* pmt) {
    if (pmt == nullptr) return E_POINTER;
    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    if (!m_connectedPin || !m_hasConnectedMt) {
        std::memset(pmt, 0, sizeof(AM_MEDIA_TYPE));
        return VFW_E_NOT_CONNECTED;
    }
    std::memset(pmt, 0, sizeof(AM_MEDIA_TYPE));
    return CopyMediaTypeFields(*pmt, m_connectedMt);
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::QueryPinInfo(PIN_INFO* pInfo) {
    if (pInfo == nullptr) return E_POINTER;
    pInfo->pFilter = m_filter;
    m_filter->AddRef();
    pInfo->dir = PINDIR_OUTPUT;
    wcscpy_s(pInfo->achName, kVcamPinName);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::QueryDirection(PIN_DIRECTION* pPinDir) {
    if (pPinDir == nullptr) return E_POINTER;
    *pPinDir = PINDIR_OUTPUT;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::QueryId(LPWSTR* Id) {
    if (Id == nullptr) return E_POINTER;
    const size_t bytes = (wcslen(kVcamPinId) + 1) * sizeof(wchar_t);
    auto* buf = static_cast<wchar_t*>(CoTaskMemAlloc(bytes));
    if (buf == nullptr) {
        *Id = nullptr;
        return E_OUTOFMEMORY;
    }
    std::memcpy(buf, kVcamPinId, bytes);
    *Id = buf;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::QueryAccept(const AM_MEDIA_TYPE* pmt) {
    return SUCCEEDED(CheckMediaType(pmt)) ? S_OK : S_FALSE;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::EnumMediaTypes(IEnumMediaTypes** ppEnum) {
    if (ppEnum == nullptr) return E_POINTER;
    auto list = BuildMediaTypeList();
    auto* enumObj = new (std::nothrow) CEnumMediaTypes(std::move(list), 0);
    if (enumObj == nullptr) {
        for (AM_MEDIA_TYPE* p : list) DeleteCoTaskMediaType(p);
        return E_OUTOFMEMORY;
    }
    *ppEnum = enumObj;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::QueryInternalConnections(IPin**, ULONG*) {
    return E_NOTIMPL;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::EndOfStream() {
    return E_UNEXPECTED;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::BeginFlush() {
    return E_UNEXPECTED;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::EndFlush() {
    return E_UNEXPECTED;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::NewSegment(REFERENCE_TIME, REFERENCE_TIME, double) {
    return E_UNEXPECTED;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::SetFormat(AM_MEDIA_TYPE* pmt) {
    if (m_filter->GetFilterState() != State_Stopped) {
        return VFW_E_NOT_STOPPED;
    }

    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    if (pmt == nullptr) {
        FreeMediaTypeFields(m_preferredMt);
        m_hasPreferredMt = false;
        return S_OK;
    }

    if (FAILED(CheckMediaType(pmt))) {
        return VFW_E_INVALIDMEDIATYPE;
    }

    if (m_connectedPin) {
        if (m_connectedPin->QueryAccept(pmt) != S_OK) {
            return VFW_E_INVALIDMEDIATYPE;
        }
    }

    HRESULT hr = CopyMediaTypeFields(m_preferredMt, *pmt);
    if (FAILED(hr)) {
        return hr;
    }
    m_hasPreferredMt = true;

    if (m_connectedPin) {
        if (IFilterGraph* graph = m_filter->GetGraph()) {
            graph->Reconnect(this);
        }
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::GetFormat(AM_MEDIA_TYPE** ppmt) {
    if (ppmt == nullptr) return E_POINTER;

    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    if (m_hasConnectedMt) {
        *ppmt = CloneMediaTypeAlloc(&m_connectedMt);
    } else {
        const std::vector<VideoCapEntry> caps = BuildCapList();
        if (m_hasPreferredMt && PreferredMtMatchesCaps(m_preferredMt, caps)) {
            *ppmt = CloneMediaTypeAlloc(&m_preferredMt);
        } else {
            const VideoCapEntry fallback{ 720, 1280, TargetFormat::NV12 };
            *ppmt = CreateMediaTypeFromCap(caps.empty() ? fallback : caps[0]);
        }
    }
    return (*ppmt != nullptr) ? S_OK : E_OUTOFMEMORY;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::GetNumberOfCapabilities(int* piCount, int* piSize) {
    if (piCount == nullptr || piSize == nullptr) return E_POINTER;
    std::lock_guard<std::recursive_mutex> lock(m_pinLock);
    m_cachedCaps = BuildCapList();
    *piCount = static_cast<int>(m_cachedCaps.size());
    *piSize = sizeof(VIDEO_STREAM_CONFIG_CAPS);
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC) {
    if (ppmt == nullptr || pSCC == nullptr) return E_POINTER;
    if (iIndex < 0) return E_INVALIDARG;

    VideoCapEntry cap{};
    {
        std::lock_guard<std::recursive_mutex> lock(m_pinLock);
        if (m_cachedCaps.empty()) {
            m_cachedCaps = BuildCapList();
        }
        if (iIndex >= static_cast<int>(m_cachedCaps.size())) return S_FALSE;
        cap = m_cachedCaps[static_cast<size_t>(iIndex)];
    }

    AM_MEDIA_TYPE* pmt = CreateMediaTypeFromCap(cap);
    if (pmt == nullptr) return E_OUTOFMEMORY;

    auto* scc = reinterpret_cast<VIDEO_STREAM_CONFIG_CAPS*>(pSCC);
    std::memset(scc, 0, sizeof(VIDEO_STREAM_CONFIG_CAPS));
    scc->guid = FORMAT_VideoInfo;
    scc->VideoStandard = AnalogVideo_None;
    scc->InputSize.cx = static_cast<LONG>(cap.width);
    scc->InputSize.cy = static_cast<LONG>(cap.height);
    scc->MinCroppingSize = scc->InputSize;
    scc->MaxCroppingSize = scc->InputSize;
    scc->CropGranularityX = 1;
    scc->CropGranularityY = 1;
    scc->MinOutputSize = scc->InputSize;
    scc->MaxOutputSize = scc->InputSize;
    scc->OutputGranularityX = 1;
    scc->OutputGranularityY = 1;
    scc->MinFrameInterval = 166666;                // 60 fps
    scc->MaxFrameInterval = 1000000;               // 10 fps
    const uint64_t imgBytes = ImageSizeForFormat(cap.format, cap.width, cap.height);
    const uint64_t maxLong = static_cast<uint64_t>((std::numeric_limits<LONG>::max)());
    scc->MinBitsPerSecond = static_cast<LONG>(std::min<uint64_t>(imgBytes * 8u * 10u, maxLong));
    scc->MaxBitsPerSecond = static_cast<LONG>(std::min<uint64_t>(imgBytes * 8u * 60u, maxLong));

    *ppmt = pmt;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::Set(
    REFGUID, DWORD, LPVOID, DWORD, LPVOID, DWORD) {
    return E_PROP_SET_UNSUPPORTED;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::Get(
    REFGUID guidPropSet,
    DWORD dwPropID,
    LPVOID,
    DWORD,
    LPVOID pPropData,
    DWORD cbPropData,
    DWORD* pcbReturned) {
    if (guidPropSet != AMPROPSETID_Pin) {
        return E_PROP_SET_UNSUPPORTED;
    }
    if (dwPropID != AMPROPERTY_PIN_CATEGORY) {
        return E_PROP_ID_UNSUPPORTED;
    }
    if (pcbReturned != nullptr) {
        *pcbReturned = sizeof(GUID);
    }
    if (pPropData == nullptr) {
        return S_OK;
    }
    if (cbPropData < sizeof(GUID)) {
        return E_UNEXPECTED;
    }
    *reinterpret_cast<GUID*>(pPropData) = PIN_CATEGORY_CAPTURE;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::QuerySupported(
    REFGUID guidPropSet,
    DWORD dwPropID,
    DWORD* pTypeSupport) {
    if (guidPropSet != AMPROPSETID_Pin) {
        return E_PROP_SET_UNSUPPORTED;
    }
    if (dwPropID != AMPROPERTY_PIN_CATEGORY) {
        return E_PROP_ID_UNSUPPORTED;
    }
    if (pTypeSupport != nullptr) {
        *pTypeSupport = KSPROPERTY_SUPPORT_GET;
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::Notify(IBaseFilter*, Quality) {
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamOutputPin::SetSink(IQualityControl* piqc) {
    m_qualitySink = piqc;
    return S_OK;
}

HRESULT CVcamOutputPin::OnStartStreaming() {
    ComPtr<IMemAllocator> alloc;
    {
        std::lock_guard<std::recursive_mutex> lock(m_pinLock);
        if (!m_connectedPin || !m_allocator) {
            return S_OK;
        }
        alloc = m_allocator;
    }

    HRESULT hr = alloc->Commit();
    if (FAILED(hr)) {
        return hr;
    }

    ResetEvent(m_stopEvent);
    ResetEvent(m_runEvent);
    ResetEvent(m_adviseEvent);
    if (!m_workerThread.joinable()) {
        m_workerThread = std::thread(&CVcamOutputPin::StreamThreadProc, this);
    }
    return S_OK;
}

HRESULT CVcamOutputPin::OnRunStreaming() {
    SetEvent(m_runEvent);
    return S_OK;
}

HRESULT CVcamOutputPin::OnPauseStreaming() {
    ResetEvent(m_runEvent);
    return S_OK;
}

HRESULT CVcamOutputPin::OnStopStreaming() {
    ComPtr<IMemAllocator> alloc;
    {
        std::lock_guard<std::recursive_mutex> lock(m_pinLock);
        alloc = m_allocator;
    }

    SetEvent(m_stopEvent);
    if (alloc) {
        alloc->Decommit();
    }
    if (m_workerThread.joinable()) {
        m_workerThread.join();
    }
    m_shmReader.Close();
    return S_OK;
}

void CVcamOutputPin::WaitNextFrame(
    IReferenceClock* clock,
    REFERENCE_TIME tStart,
    REFERENCE_TIME targetStreamTime,
    REFERENCE_TIME frameInterval,
    HANDLE hFallbackTimer) {
    if (clock != nullptr) {
        REFERENCE_TIME now = 0;
        if (SUCCEEDED(clock->GetTime(&now))) {
            const REFERENCE_TIME targetClock = tStart + targetStreamTime;
            if (now < targetClock) {
                DWORD_PTR cookie = 0;
                ResetEvent(m_adviseEvent);
                if (SUCCEEDED(clock->AdviseTime(
                        tStart,
                        targetStreamTime,
                        reinterpret_cast<HEVENT>(m_adviseEvent),
                        &cookie))) {
                    HANDLE waitHandles[2] = { m_stopEvent, m_adviseEvent };
                    const DWORD res = WaitForMultipleObjects(2, waitHandles, FALSE, 200);
                    if (res != WAIT_OBJECT_0 + 1) {
                        clock->Unadvise(cookie);
                    }
                    return;
                }
            } else {
                return;
            }
        }
    }

    if (hFallbackTimer != nullptr) {
        LARGE_INTEGER due{};
        due.QuadPart = -std::max<REFERENCE_TIME>(1, frameInterval);
        if (SetWaitableTimer(hFallbackTimer, &due, 0, nullptr, nullptr, FALSE)) {
            HANDLE waitHandles[2] = { m_stopEvent, hFallbackTimer };
            WaitForMultipleObjects(2, waitHandles, FALSE, 100);
            return;
        }
    }

    const DWORD ms = static_cast<DWORD>(std::clamp<REFERENCE_TIME>(frameInterval / 10000, 1, 100));
    WaitForSingleObject(m_stopEvent, ms);
}

void CVcamOutputPin::StreamThreadProc() {
    const HRESULT coHr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);

    HANDLE hFallbackTimer = CreateWaitableTimerExW(
        nullptr,
        nullptr,
        CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
        TIMER_ALL_ACCESS);
    if (hFallbackTimer == nullptr) {
        hFallbackTimer = CreateWaitableTimerW(nullptr, FALSE, nullptr);
    }

    TargetFormatDesc desc{};
    REFERENCE_TIME frameInterval = kDefaultFrameInterval;
    ComPtr<IMemAllocator> alloc;
    ComPtr<IMemInputPin> inputPin;
    ComPtr<IPin> connectedPin;

    {
        std::lock_guard<std::recursive_mutex> lock(m_pinLock);
        if (!m_hasConnectedMt || !ExtractTargetFormatDesc(&m_connectedMt, desc, frameInterval)) {
            if (hFallbackTimer != nullptr) CloseHandle(hFallbackTimer);
            if (SUCCEEDED(coHr)) CoUninitialize();
            return;
        }
        alloc = m_allocator;
        inputPin = m_inputPin;
        connectedPin = m_connectedPin;
    }

    uint64_t frameIndex = 0;
    REFERENCE_TIME lastSampleEnd = 0;
    REFERENCE_TIME nextStreamTarget = 0;
    uint32_t lastDynProbeW = desc.width;
    uint32_t lastDynProbeH = desc.height;

    for (;;) {
        HANDLE startHandles[2] = { m_stopEvent, m_runEvent };
        const DWORD waitRun = WaitForMultipleObjects(2, startHandles, FALSE, INFINITE);
        if (waitRun != WAIT_OBJECT_0 + 1) {
            break;
        }

        ComPtr<IReferenceClock> clock;
        REFERENCE_TIME tStart = 0;
        m_filter->GetClockAndStart(clock, tStart);

        if (frameIndex > 0) {
            WaitNextFrame(clock.Get(), tStart, nextStreamTarget, frameInterval, hFallbackTimer);
            if (WaitForSingleObject(m_stopEvent, 0) == WAIT_OBJECT_0) {
                break;
            }
        }

        ComPtr<IMediaSample> sample;
        HRESULT hr = alloc->GetBuffer(sample.ReleaseAndGetAddressOf(), nullptr, nullptr, 0);
        if (FAILED(hr) || !sample) {
            break;
        }

        // Handle downstream dynamic format/stride change if attached to the sample.
        AM_MEDIA_TYPE* pDynMt = nullptr;
        if (sample->GetMediaType(&pDynMt) == S_OK && pDynMt != nullptr) {
            TargetFormatDesc newDesc{};
            REFERENCE_TIME newInterval = frameInterval;
            if (ExtractTargetFormatDesc(pDynMt, newDesc, newInterval)) {
                desc = newDesc;
                frameInterval = newInterval;
            }
            DeleteCoTaskMediaType(pDynMt);
        }

        BYTE* pBuf = nullptr;
        hr = sample->GetPointer(&pBuf);
        const long bufSize = sample->GetSize();
        size_t requiredBytes = FrameConverter::RequiredBufferSize(desc);

        if (SUCCEEDED(hr) && pBuf != nullptr && bufSize >= static_cast<long>(requiredBytes)) {
            ShmFrameSnapshot snap{};
            const ShmReadStatus shmStatus = m_shmReader.ReadLatest(snap);

            if (shmStatus == ShmReadStatus::Live &&
                snap.width >= 2u && snap.height >= 2u &&
                (snap.width != desc.width || snap.height != desc.height) &&
                (snap.width != lastDynProbeW || snap.height != lastDynProbeH)) {
                lastDynProbeW = snap.width;
                lastDynProbeH = snap.height;

                const bool orientDiff = (snap.height > snap.width) != (desc.height > desc.width);
                const double snapAspect = static_cast<double>(snap.width) / static_cast<double>(snap.height);
                const double descAspect = static_cast<double>(desc.width) / static_cast<double>(std::max(1u, desc.height));
                const bool aspectDiff = std::abs(snapAspect - descAspect) > 0.06;
                const bool evenOk = ((desc.format != TargetFormat::NV12 && desc.format != TargetFormat::YUY2) ||
                                     (((snap.width & 1u) == 0) && ((snap.height & 1u) == 0)));

                if ((orientDiff || aspectDiff) && evenOk && connectedPin) {
                    TargetFormatDesc newDesc = desc;
                    newDesc.width = snap.width;
                    newDesc.height = snap.height;
                    newDesc.stride = StrideForFormat(desc.format, snap.width);
                    const size_t newReqBytes = FrameConverter::RequiredBufferSize(newDesc);
                    if (newReqBytes > 0 && newReqBytes <= static_cast<size_t>(bufSize)) {
                        if (AM_MEDIA_TYPE* newMt = CreateMediaTypeFromParams(
                                desc.format, snap.width, snap.height, desc.bottomUp, frameInterval)) {
                            if (connectedPin->QueryAccept(newMt) == S_OK &&
                                SUCCEEDED(sample->SetMediaType(newMt))) {
                                desc = newDesc;
                                requiredBytes = newReqBytes;
                                std::lock_guard<std::recursive_mutex> lock(m_pinLock);
                                CopyMediaTypeFields(m_connectedMt, *newMt);
                            }
                            DeleteCoTaskMediaType(newMt);
                        }
                    }
                }
            }

            bool ok = false;
            if (shmStatus == ShmReadStatus::Live) {
                ok = m_converter.ConvertLiveFrame(snap, desc, pBuf, static_cast<size_t>(bufSize));
            }
            if (!ok) {
                m_converter.RenderStandby(
                    desc,
                    frameIndex,
                    shmStatus == ShmReadStatus::Paused,
                    snap.flipFlags,
                    pBuf,
                    static_cast<size_t>(bufSize));
            }

            sample->SetActualDataLength(static_cast<long>(requiredBytes));
        }

        REFERENCE_TIME rtSampleStart = lastSampleEnd;
        if (clock) {
            REFERENCE_TIME rtNow = 0;
            if (SUCCEEDED(clock->GetTime(&rtNow))) {
                const REFERENCE_TIME streamNow = (rtNow > tStart) ? (rtNow - tStart) : 0;
                rtSampleStart = std::max(lastSampleEnd, streamNow);
            }
        } else {
            rtSampleStart = static_cast<REFERENCE_TIME>(frameIndex) * frameInterval;
        }
        const REFERENCE_TIME rtSampleEnd = rtSampleStart + frameInterval;
        lastSampleEnd = rtSampleEnd;

        if (frameIndex == 0) {
            nextStreamTarget = rtSampleEnd;
        } else {
            nextStreamTarget += frameInterval;
            if (rtSampleStart > nextStreamTarget + frameInterval * 3) {
                nextStreamTarget = rtSampleEnd;
            }
        }

        REFERENCE_TIME rtStartCopy = rtSampleStart;
        REFERENCE_TIME rtEndCopy = rtSampleEnd;
        sample->SetTime(&rtStartCopy, &rtEndCopy);
        sample->SetSyncPoint(TRUE);
        sample->SetPreroll(FALSE);
        sample->SetDiscontinuity(frameIndex == 0 ? TRUE : FALSE);

        hr = inputPin->Receive(sample.Get());
        if (hr != S_OK) {
            break;
        }

        ++frameIndex;
    }

    if (hFallbackTimer != nullptr) {
        CloseHandle(hFallbackTimer);
    }
    if (SUCCEEDED(coHr)) {
        CoUninitialize();
    }
}

// ============================================================================
// CVcamFilter
// ============================================================================

CVcamFilter::CVcamFilter() : m_outputPin(this) {
    DllAddRef();
    wcscpy_s(m_name, kVcamFriendlyName);
}

CVcamFilter::~CVcamFilter() {
    m_outputPin.OnStopStreaming();
    DllRelease();
}

HRESULT STDMETHODCALLTYPE CVcamFilter::QueryInterface(REFIID riid, void** ppv) {
    if (ppv == nullptr) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IPersist || riid == IID_IMediaFilter || riid == IID_IBaseFilter) {
        *ppv = static_cast<IBaseFilter*>(this);
    } else if (riid == IID_IAMFilterMiscFlags) {
        *ppv = static_cast<IAMFilterMiscFlags*>(this);
    } else {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

ULONG STDMETHODCALLTYPE CVcamFilter::AddRef() {
    return static_cast<ULONG>(m_refCount.fetch_add(1, std::memory_order_relaxed) + 1);
}

ULONG STDMETHODCALLTYPE CVcamFilter::Release() {
    const long c = m_refCount.fetch_sub(1, std::memory_order_acq_rel) - 1;
    if (c == 0) {
        delete this;
    }
    return static_cast<ULONG>(c);
}

HRESULT STDMETHODCALLTYPE CVcamFilter::GetClassID(CLSID* pClassID) {
    if (pClassID == nullptr) return E_POINTER;
    *pClassID = CLSID_PhoneLinkVirtualCam;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::Stop() {
    {
        std::lock_guard<std::recursive_mutex> lock(m_filterLock);
        if (m_state == State_Stopped) {
            return S_OK;
        }
    }

    // Stop streaming outside m_filterLock to prevent deadlocks with Receive/GetClockAndStart.
    m_outputPin.OnStopStreaming();

    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    m_state = State_Stopped;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::Pause() {
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    if (m_state == State_Paused) {
        return S_OK;
    }
    if (m_state == State_Stopped) {
        const HRESULT hr = m_outputPin.OnStartStreaming();
        if (FAILED(hr)) {
            return hr;
        }
    } else if (m_state == State_Running) {
        m_outputPin.OnPauseStreaming();
    }
    m_state = State_Paused;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::Run(REFERENCE_TIME tStart) {
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    m_tStart = tStart;
    if (m_state == State_Stopped) {
        const HRESULT hr = m_outputPin.OnStartStreaming();
        if (FAILED(hr)) {
            return hr;
        }
    }
    m_state = State_Running;
    return m_outputPin.OnRunStreaming();
}

HRESULT STDMETHODCALLTYPE CVcamFilter::GetState(DWORD, FILTER_STATE* State) {
    if (State == nullptr) return E_POINTER;
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    *State = m_state;
    return (m_state == State_Paused) ? VFW_S_CANT_CUE : S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::SetSyncSource(IReferenceClock* pClock) {
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    m_clock.Reset();
    if (pClock != nullptr) {
        pClock->AddRef();
        *m_clock.ReleaseAndGetAddressOf() = pClock;
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::GetSyncSource(IReferenceClock** pClock) {
    if (pClock == nullptr) return E_POINTER;
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    *pClock = m_clock.Get();
    if (*pClock != nullptr) {
        (*pClock)->AddRef();
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::EnumPins(IEnumPins** ppEnum) {
    if (ppEnum == nullptr) return E_POINTER;
    auto* enumObj = new (std::nothrow) CEnumPins(&m_outputPin, 0);
    if (enumObj == nullptr) return E_OUTOFMEMORY;
    *ppEnum = enumObj;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::FindPin(LPCWSTR Id, IPin** ppPin) {
    if (ppPin == nullptr) return E_POINTER;
    if (Id != nullptr && wcscmp(Id, kVcamPinId) == 0) {
        *ppPin = &m_outputPin;
        (*ppPin)->AddRef();
        return S_OK;
    }
    *ppPin = nullptr;
    return VFW_E_NOT_FOUND;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::QueryFilterInfo(FILTER_INFO* pInfo) {
    if (pInfo == nullptr) return E_POINTER;
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    wcscpy_s(pInfo->achName, m_name);
    pInfo->pGraph = m_graph;
    if (pInfo->pGraph != nullptr) {
        pInfo->pGraph->AddRef();
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName) {
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    m_graph = pGraph;
    if (pName != nullptr) {
        wcsncpy_s(m_name, pName, _TRUNCATE);
    } else {
        wcscpy_s(m_name, kVcamFriendlyName);
    }
    return S_OK;
}

HRESULT STDMETHODCALLTYPE CVcamFilter::QueryVendorInfo(LPWSTR* pVendorInfo) {
    if (pVendorInfo == nullptr) return E_POINTER;
    *pVendorInfo = nullptr;
    return E_NOTIMPL;
}

ULONG STDMETHODCALLTYPE CVcamFilter::GetMiscFlags() {
    return AM_FILTER_MISC_FLAGS_IS_SOURCE;
}

FILTER_STATE CVcamFilter::GetFilterState() const noexcept {
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    return m_state;
}

void CVcamFilter::GetClockAndStart(ComPtr<IReferenceClock>& outClock, REFERENCE_TIME& outStart) const noexcept {
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    outClock = m_clock;
    outStart = m_tStart;
}

IFilterGraph* CVcamFilter::GetGraph() const noexcept {
    std::lock_guard<std::recursive_mutex> lock(m_filterLock);
    return m_graph;
}

// ============================================================================
// CClassFactory
// ============================================================================

CClassFactory::CClassFactory() {
    DllAddRef();
}

CClassFactory::~CClassFactory() {
    DllRelease();
}

HRESULT STDMETHODCALLTYPE CClassFactory::QueryInterface(REFIID riid, void** ppv) {
    if (ppv == nullptr) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IClassFactory) {
        *ppv = static_cast<IClassFactory*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

ULONG STDMETHODCALLTYPE CClassFactory::AddRef() {
    return static_cast<ULONG>(m_refCount.fetch_add(1, std::memory_order_relaxed) + 1);
}

ULONG STDMETHODCALLTYPE CClassFactory::Release() {
    const long c = m_refCount.fetch_sub(1, std::memory_order_acq_rel) - 1;
    if (c == 0) {
        delete this;
    }
    return static_cast<ULONG>(c);
}

HRESULT STDMETHODCALLTYPE CClassFactory::CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) {
    if (ppv == nullptr) return E_POINTER;
    *ppv = nullptr;
    if (pUnkOuter != nullptr) return CLASS_E_NOAGGREGATION;

    auto* filter = new (std::nothrow) CVcamFilter();
    if (filter == nullptr) return E_OUTOFMEMORY;

    const HRESULT hr = filter->QueryInterface(riid, ppv);
    filter->Release();
    return hr;
}

HRESULT STDMETHODCALLTYPE CClassFactory::LockServer(BOOL fLock) {
    if (fLock) {
        DllAddRef();
    } else {
        DllRelease();
    }
    return S_OK;
}

} // namespace vcam
