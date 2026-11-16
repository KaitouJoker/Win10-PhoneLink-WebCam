#pragma once

#include "frame_convert.h"
#include "shm_reader.h"
#include "vcam_guids.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <dshow.h>
#include <dvdmedia.h>
#include <ks.h>
#include <ksmedia.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace vcam {

void DllAddRef() noexcept;
void DllRelease() noexcept;

struct VideoCapEntry {
    uint32_t width;
    uint32_t height;
    TargetFormat format;
};

class CVcamFilter;

// Simple RAII COM smart pointer to avoid ATL dependency.
template <typename T>
class ComPtr {
public:
    ComPtr() noexcept = default;
    ~ComPtr() { Reset(); }

    ComPtr(const ComPtr& other) noexcept : m_ptr(other.m_ptr) {
        if (m_ptr) m_ptr->AddRef();
    }
    ComPtr(ComPtr&& other) noexcept : m_ptr(other.m_ptr) {
        other.m_ptr = nullptr;
    }
    ComPtr& operator=(const ComPtr& other) noexcept {
        if (this != &other) {
            Reset();
            m_ptr = other.m_ptr;
            if (m_ptr) m_ptr->AddRef();
        }
        return *this;
    }
    ComPtr& operator=(ComPtr&& other) noexcept {
        if (this != &other) {
            Reset();
            m_ptr = other.m_ptr;
            other.m_ptr = nullptr;
        }
        return *this;
    }

    void Reset() noexcept {
        if (m_ptr) {
            m_ptr->Release();
            m_ptr = nullptr;
        }
    }

    T* Get() const noexcept { return m_ptr; }
    T** ReleaseAndGetAddressOf() noexcept {
        Reset();
        return &m_ptr;
    }
    T* operator->() const noexcept { return m_ptr; }
    explicit operator bool() const noexcept { return m_ptr != nullptr; }

private:
    T* m_ptr = nullptr;
};

class CVcamOutputPin final
    : public IPin
    , public IAMStreamConfig
    , public IKsPropertySet
    , public IQualityControl {
public:
    explicit CVcamOutputPin(CVcamFilter* filter);
    ~CVcamOutputPin();

    CVcamOutputPin(const CVcamOutputPin&) = delete;
    CVcamOutputPin& operator=(const CVcamOutputPin&) = delete;

    // IUnknown (delegated to owning CVcamFilter)
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override;
    ULONG STDMETHODCALLTYPE AddRef() override;
    ULONG STDMETHODCALLTYPE Release() override;

    // IPin
    HRESULT STDMETHODCALLTYPE Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) override;
    HRESULT STDMETHODCALLTYPE ReceiveConnection(IPin* pConnector, const AM_MEDIA_TYPE* pmt) override;
    HRESULT STDMETHODCALLTYPE Disconnect() override;
    HRESULT STDMETHODCALLTYPE ConnectedTo(IPin** pPin) override;
    HRESULT STDMETHODCALLTYPE ConnectionMediaType(AM_MEDIA_TYPE* pmt) override;
    HRESULT STDMETHODCALLTYPE QueryPinInfo(PIN_INFO* pInfo) override;
    HRESULT STDMETHODCALLTYPE QueryDirection(PIN_DIRECTION* pPinDir) override;
    HRESULT STDMETHODCALLTYPE QueryId(LPWSTR* Id) override;
    HRESULT STDMETHODCALLTYPE QueryAccept(const AM_MEDIA_TYPE* pmt) override;
    HRESULT STDMETHODCALLTYPE EnumMediaTypes(IEnumMediaTypes** ppEnum) override;
    HRESULT STDMETHODCALLTYPE QueryInternalConnections(IPin** apPin, ULONG* nPin) override;
    HRESULT STDMETHODCALLTYPE EndOfStream() override;
    HRESULT STDMETHODCALLTYPE BeginFlush() override;
    HRESULT STDMETHODCALLTYPE EndFlush() override;
    HRESULT STDMETHODCALLTYPE NewSegment(REFERENCE_TIME tStart, REFERENCE_TIME tStop, double dRate) override;

    // IAMStreamConfig
    HRESULT STDMETHODCALLTYPE SetFormat(AM_MEDIA_TYPE* pmt) override;
    HRESULT STDMETHODCALLTYPE GetFormat(AM_MEDIA_TYPE** ppmt) override;
    HRESULT STDMETHODCALLTYPE GetNumberOfCapabilities(int* piCount, int* piSize) override;
    HRESULT STDMETHODCALLTYPE GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC) override;

    // IKsPropertySet
    HRESULT STDMETHODCALLTYPE Set(
        REFGUID guidPropSet,
        DWORD dwPropID,
        LPVOID pInstanceData,
        DWORD cbInstanceData,
        LPVOID pPropData,
        DWORD cbPropData) override;
    HRESULT STDMETHODCALLTYPE Get(
        REFGUID guidPropSet,
        DWORD dwPropID,
        LPVOID pInstanceData,
        DWORD cbInstanceData,
        LPVOID pPropData,
        DWORD cbPropData,
        DWORD* pcbReturned) override;
    HRESULT STDMETHODCALLTYPE QuerySupported(
        REFGUID guidPropSet,
        DWORD dwPropID,
        DWORD* pTypeSupport) override;

    // IQualityControl
    HRESULT STDMETHODCALLTYPE Notify(IBaseFilter* pSelf, Quality q) override;
    HRESULT STDMETHODCALLTYPE SetSink(IQualityControl* piqc) override;

    // Lifecycle called by CVcamFilter
    HRESULT OnStartStreaming();
    HRESULT OnRunStreaming();
    HRESULT OnPauseStreaming();
    HRESULT OnStopStreaming();

    std::vector<AM_MEDIA_TYPE*> BuildMediaTypeList() const;

private:
    std::vector<VideoCapEntry> BuildCapList() const;
    static bool PreferredMtMatchesCaps(const AM_MEDIA_TYPE& mt, const std::vector<VideoCapEntry>& caps);
    HRESULT CheckMediaType(const AM_MEDIA_TYPE* pmt) const;
    bool MatchesPartialMediaType(const AM_MEDIA_TYPE* partial, const AM_MEDIA_TYPE* candidate) const;
    HRESULT AttemptConnect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt);
    static bool ExtractTargetFormatDesc(const AM_MEDIA_TYPE* pmt, TargetFormatDesc& outDesc, REFERENCE_TIME& outInterval);
    static AM_MEDIA_TYPE* CreateMediaTypeFromParams(TargetFormat fmt, uint32_t width, uint32_t height, bool bottomUp, REFERENCE_TIME interval);
    static AM_MEDIA_TYPE* CreateMediaTypeFromCap(const VideoCapEntry& cap);

    void StreamThreadProc();
    void WaitNextFrame(
        IReferenceClock* clock,
        REFERENCE_TIME tStart,
        REFERENCE_TIME targetStreamTime,
        REFERENCE_TIME frameInterval,
        HANDLE hFallbackTimer);

    CVcamFilter* m_filter = nullptr;

    mutable std::recursive_mutex m_pinLock;
    mutable std::vector<VideoCapEntry> m_cachedCaps;
    ComPtr<IPin> m_connectedPin;
    ComPtr<IMemInputPin> m_inputPin;
    ComPtr<IMemAllocator> m_allocator;

    AM_MEDIA_TYPE m_connectedMt{};
    bool m_hasConnectedMt = false;

    AM_MEDIA_TYPE m_preferredMt{};
    bool m_hasPreferredMt = false;

    IQualityControl* m_qualitySink = nullptr; // weak pointer per DirectShow rules

    HANDLE m_stopEvent = nullptr;
    HANDLE m_runEvent = nullptr;
    HANDLE m_adviseEvent = nullptr;
    std::thread m_workerThread;

    ShmReader m_shmReader;
    FrameConverter m_converter;
};

class CVcamFilter final
    : public IBaseFilter
    , public IAMFilterMiscFlags {
public:
    CVcamFilter();
    ~CVcamFilter();

    CVcamFilter(const CVcamFilter&) = delete;
    CVcamFilter& operator=(const CVcamFilter&) = delete;

    // IUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override;
    ULONG STDMETHODCALLTYPE AddRef() override;
    ULONG STDMETHODCALLTYPE Release() override;

    // IPersist
    HRESULT STDMETHODCALLTYPE GetClassID(CLSID* pClassID) override;

    // IMediaFilter
    HRESULT STDMETHODCALLTYPE Stop() override;
    HRESULT STDMETHODCALLTYPE Pause() override;
    HRESULT STDMETHODCALLTYPE Run(REFERENCE_TIME tStart) override;
    HRESULT STDMETHODCALLTYPE GetState(DWORD dwMilliSecsTimeout, FILTER_STATE* State) override;
    HRESULT STDMETHODCALLTYPE SetSyncSource(IReferenceClock* pClock) override;
    HRESULT STDMETHODCALLTYPE GetSyncSource(IReferenceClock** pClock) override;

    // IBaseFilter
    HRESULT STDMETHODCALLTYPE EnumPins(IEnumPins** ppEnum) override;
    HRESULT STDMETHODCALLTYPE FindPin(LPCWSTR Id, IPin** ppPin) override;
    HRESULT STDMETHODCALLTYPE QueryFilterInfo(FILTER_INFO* pInfo) override;
    HRESULT STDMETHODCALLTYPE JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName) override;
    HRESULT STDMETHODCALLTYPE QueryVendorInfo(LPWSTR* pVendorInfo) override;

    // IAMFilterMiscFlags
    ULONG STDMETHODCALLTYPE GetMiscFlags() override;

    FILTER_STATE GetFilterState() const noexcept;
    void GetClockAndStart(ComPtr<IReferenceClock>& outClock, REFERENCE_TIME& outStart) const noexcept;
    IFilterGraph* GetGraph() const noexcept;

private:
    std::atomic<long> m_refCount{1};
    mutable std::recursive_mutex m_filterLock;

    FILTER_STATE m_state = State_Stopped;
    REFERENCE_TIME m_tStart = 0;
    ComPtr<IReferenceClock> m_clock;
    IFilterGraph* m_graph = nullptr; // weak pointer to avoid circular ref with FilterGraph
    wchar_t m_name[128]{};

    CVcamOutputPin m_outputPin;
};

class CClassFactory final : public IClassFactory {
public:
    CClassFactory();
    ~CClassFactory();

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override;
    ULONG STDMETHODCALLTYPE AddRef() override;
    ULONG STDMETHODCALLTYPE Release() override;

    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) override;
    HRESULT STDMETHODCALLTYPE LockServer(BOOL fLock) override;

private:
    std::atomic<long> m_refCount{1};
};

} // namespace vcam
