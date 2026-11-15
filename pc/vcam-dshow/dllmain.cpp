#include "vcam_filter.h"
#include "vcam_guids.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <dbt.h>
#include <dshow.h>
#include <objbase.h>
#include <shlwapi.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

namespace {

HMODULE g_hModule = nullptr;
std::atomic<long> g_dllRefCount{0};

constexpr wchar_t kVideoInputCategoryClsidStr[] = L"{860BB310-5D01-11d0-BD3B-00A0C911CE86}";

// Build serialized FilterData blob matching IFilterMapper2 v2 format as fallback when
// non-elevated devenum.dll refuses HKLM writes.
std::vector<uint8_t> BuildFallbackFilterDataBlob() {
    // Minimal REGFILTER2 v2 binary layout:
    // Header (16 bytes): dwVersion=2, dwMerit=MERIT_DO_NOT_USE(0x00200000), cPins=1, reserved=0
    // Pin (24 bytes): signature='0pi3', dwFlags=REG_PINFLAG_B_OUTPUT(8), cInstances=1? -> Wait:
    // Matching observed devenum format:
    // [dwVersion=2][dwMerit=0x200000][cPins=1][0]
    // [tag='0pi3'][dwFlags=8][dwReserved=0][nMediaTypes=1][nMediums=0][offsetPinCat=0]
    // [tag='0ty3'][0][offsetMajor][offsetMinor]
    // [16B MEDIATYPE_Video][16B GUID_NULL]
    const uint32_t offsetMajor = 56;
    const uint32_t offsetMinor = 72;

    std::vector<uint8_t> buf(88, 0);
    auto writeU32 = [&](size_t off, uint32_t val) {
        std::memcpy(buf.data() + off, &val, sizeof(val));
    };

    writeU32(0, 2);                // version = 2
    writeU32(4, MERIT_DO_NOT_USE); // 0x00200000
    writeU32(8, 1);                // 1 pin
    writeU32(12, 0);               // reserved

    // Pin entry at offset 16 (24 bytes)
    writeU32(16, 0x33697030);      // '0pi3'
    writeU32(20, REG_PINFLAG_B_OUTPUT);
    writeU32(24, 0);               // reserved / rendered? 0
    writeU32(28, 1);               // 1 media type summary
    writeU32(32, 0);               // 0 mediums
    writeU32(36, 0);               // no category offset

    // Type entry at offset 40 (16 bytes)
    writeU32(40, 0x33797430);      // '0ty3'
    writeU32(44, 0);               // reserved
    writeU32(48, offsetMajor);
    writeU32(52, offsetMinor);

    std::memcpy(buf.data() + offsetMajor, &MEDIATYPE_Video, sizeof(GUID));
    std::memcpy(buf.data() + offsetMinor, &GUID_NULL, sizeof(GUID));
    return buf;
}

LSTATUS WriteStringValue(HKEY hKey, const wchar_t* name, const wchar_t* value) {
    const DWORD bytes = static_cast<DWORD>((wcslen(value) + 1) * sizeof(wchar_t));
    return RegSetValueExW(hKey, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(value), bytes);
}

HRESULT RegisterComServerUnderRoot(HKEY classesRoot, const wchar_t* dllPath) {
    wchar_t clsidSubKey[128]{};
    swprintf_s(clsidSubKey, L"CLSID\\%s", ::kVcamClsidString);

    HKEY hClsidKey = nullptr;
    LSTATUS st = RegCreateKeyExW(
        classesRoot,
        clsidSubKey,
        0,
        nullptr,
        REG_OPTION_NON_VOLATILE,
        KEY_WRITE,
        nullptr,
        &hClsidKey,
        nullptr);
    if (st != ERROR_SUCCESS) {
        return HRESULT_FROM_WIN32(st);
    }

    st = WriteStringValue(hClsidKey, nullptr, ::kVcamFriendlyName);
    if (st == ERROR_SUCCESS) {
        HKEY hInproc = nullptr;
        st = RegCreateKeyExW(
            hClsidKey,
            L"InprocServer32",
            0,
            nullptr,
            REG_OPTION_NON_VOLATILE,
            KEY_WRITE,
            nullptr,
            &hInproc,
            nullptr);
        if (st == ERROR_SUCCESS) {
            st = WriteStringValue(hInproc, nullptr, dllPath);
            if (st == ERROR_SUCCESS) {
                st = WriteStringValue(hInproc, L"ThreadingModel", L"Both");
            }
            RegCloseKey(hInproc);
        }
    }
    RegCloseKey(hClsidKey);
    return HRESULT_FROM_WIN32(st);
}

HRESULT OpenCategoryInstanceKey(HKEY classesRoot, HKEY& outKey) {
    wchar_t instSubKey[256]{};
    swprintf_s(
        instSubKey,
        L"CLSID\\%s\\Instance\\%s",
        kVideoInputCategoryClsidStr,
        ::kVcamClsidString);

    const LSTATUS st = RegCreateKeyExW(
        classesRoot,
        instSubKey,
        0,
        nullptr,
        REG_OPTION_NON_VOLATILE,
        KEY_WRITE,
        nullptr,
        &outKey,
        nullptr);
    return HRESULT_FROM_WIN32(st);
}

void EnsureCategoryDescription(HKEY classesRoot) {
    HKEY hInst = nullptr;
    if (SUCCEEDED(OpenCategoryInstanceKey(classesRoot, hInst))) {
        WriteStringValue(hInst, L"Description", ::kVcamFriendlyName);
        RegCloseKey(hInst);
    }
}

HRESULT RegisterCategoryInstanceDirect(HKEY classesRoot) {
    HKEY hInst = nullptr;
    HRESULT hr = OpenCategoryInstanceKey(classesRoot, hInst);
    if (FAILED(hr)) {
        return hr;
    }

    LSTATUS st = WriteStringValue(hInst, L"FriendlyName", ::kVcamFriendlyName);
    if (st == ERROR_SUCCESS) {
        st = WriteStringValue(hInst, L"Description", ::kVcamFriendlyName);
    }
    if (st == ERROR_SUCCESS) {
        st = WriteStringValue(hInst, L"CLSID", ::kVcamClsidString);
    }
    if (st == ERROR_SUCCESS) {
        const auto blob = BuildFallbackFilterDataBlob();
        st = RegSetValueExW(
            hInst,
            L"FilterData",
            0,
            REG_BINARY,
            blob.data(),
            static_cast<DWORD>(blob.size()));
    }
    RegCloseKey(hInst);
    return HRESULT_FROM_WIN32(st);
}

void BroadcastDeviceChange() noexcept {
    DWORD_PTR dwResult = 0;
    SendMessageTimeoutW(
        HWND_BROADCAST,
        WM_DEVICECHANGE,
        DBT_DEVNODES_CHANGED,
        0,
        SMTO_ABORTIFHUNG,
        200,
        &dwResult);
}

HRESULT RegisterWithFilterMapper(HKEY classesRootOverride) {
    const HRESULT coHr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool needUninit = SUCCEEDED(coHr);

    vcam::ComPtr<IFilterMapper2> mapper;
    HRESULT hr = CoCreateInstance(
        CLSID_FilterMapper2,
        nullptr,
        CLSCTX_INPROC_SERVER,
        IID_IFilterMapper2,
        reinterpret_cast<void**>(mapper.ReleaseAndGetAddressOf()));

    if (SUCCEEDED(hr) && mapper) {
        REGPINTYPES pinTypes[] = {
            { &MEDIATYPE_Video, &MEDIASUBTYPE_NV12 },
            { &MEDIATYPE_Video, &MEDIASUBTYPE_YUY2 },
            { &MEDIATYPE_Video, &MEDIASUBTYPE_RGB32 },
            { &MEDIATYPE_Video, &MEDIASUBTYPE_RGB24 },
        };

        REGFILTERPINS2 pinDesc{};
        pinDesc.dwFlags = REG_PINFLAG_B_OUTPUT;
        pinDesc.cInstances = 1;
        pinDesc.nMediaTypes = static_cast<UINT>(sizeof(pinTypes) / sizeof(pinTypes[0]));
        pinDesc.lpMediaType = pinTypes;
        pinDesc.nMediums = 0;
        pinDesc.lpMedium = nullptr;
        pinDesc.clsPinCategory = &PIN_CATEGORY_CAPTURE;

        REGFILTER2 rf2{};
        rf2.dwVersion = 2;
        rf2.dwMerit = MERIT_DO_NOT_USE;
        rf2.cPins2 = 1;
        rf2.rgPins2 = &pinDesc;

        bool overridden = false;
        if (classesRootOverride != nullptr) {
            if (RegOverridePredefKey(HKEY_CLASSES_ROOT, classesRootOverride) == ERROR_SUCCESS) {
                overridden = true;
            }
        }

        vcam::ComPtr<IMoniker> moniker;
        hr = mapper->RegisterFilter(
            CLSID_PhoneLinkVirtualCam,
            ::kVcamFriendlyName,
            moniker.ReleaseAndGetAddressOf(),
            &CLSID_VideoInputDeviceCategory,
            ::kVcamClsidString,
            &rf2);

        if (overridden) {
            RegOverridePredefKey(HKEY_CLASSES_ROOT, nullptr);
        }
    }

    mapper.Reset();
    if (needUninit) {
        CoUninitialize();
    }
    return hr;
}

void DeleteKeysUnderRoot(HKEY classesRoot) {
    wchar_t clsidSubKey[128]{};
    swprintf_s(clsidSubKey, L"CLSID\\%s", ::kVcamClsidString);
    SHDeleteKeyW(classesRoot, clsidSubKey);

    wchar_t instSubKey[256]{};
    swprintf_s(
        instSubKey,
        L"CLSID\\%s\\Instance\\%s",
        kVideoInputCategoryClsidStr,
        ::kVcamClsidString);
    SHDeleteKeyW(classesRoot, instSubKey);
}

} // namespace

namespace vcam {

void DllAddRef() noexcept {
    g_dllRefCount.fetch_add(1, std::memory_order_relaxed);
}

void DllRelease() noexcept {
    g_dllRefCount.fetch_sub(1, std::memory_order_acq_rel);
}

} // namespace vcam

BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID) {
    if (ul_reason_for_call == DLL_PROCESS_ATTACH) {
        g_hModule = hModule;
        DisableThreadLibraryCalls(hModule);
    }
    return TRUE;
}

extern "C" HRESULT __stdcall DllGetClassObject(REFCLSID rclsid, REFIID riid, LPVOID* ppv) {
    if (ppv == nullptr) return E_POINTER;
    *ppv = nullptr;
    if (rclsid != CLSID_PhoneLinkVirtualCam) {
        return CLASS_E_CLASSNOTAVAILABLE;
    }

    auto* factory = new (std::nothrow) vcam::CClassFactory();
    if (factory == nullptr) {
        return E_OUTOFMEMORY;
    }

    const HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

extern "C" HRESULT __stdcall DllCanUnloadNow() {
    return (g_dllRefCount.load(std::memory_order_acquire) == 0) ? S_OK : S_FALSE;
}

extern "C" HRESULT __stdcall DllRegisterServer() {
    wchar_t dllPath[MAX_PATH]{};
    const DWORD pathLen = GetModuleFileNameW(g_hModule, dllPath, MAX_PATH);
    if (pathLen == 0 || pathLen >= MAX_PATH) {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    HRESULT lmHr = E_ACCESSDENIED;
    HKEY hLmClasses = nullptr;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"Software\\Classes", 0, KEY_READ | KEY_WRITE, &hLmClasses) == ERROR_SUCCESS) {
        lmHr = RegisterComServerUnderRoot(hLmClasses, dllPath);
        if (SUCCEEDED(lmHr)) {
            lmHr = RegisterWithFilterMapper(nullptr);
            if (FAILED(lmHr)) {
                lmHr = RegisterCategoryInstanceDirect(hLmClasses);
            } else {
                EnsureCategoryDescription(hLmClasses);
            }
        }
        RegCloseKey(hLmClasses);
    }

    // Always register under HKCU as well so current-user DirectShow callers work without elevation.
    HRESULT cuHr = E_FAIL;
    HKEY hCuClasses = nullptr;
    const LSTATUS cuStatus = RegCreateKeyExW(
        HKEY_CURRENT_USER,
        L"Software\\Classes",
        0,
        nullptr,
        REG_OPTION_NON_VOLATILE,
        KEY_ALL_ACCESS,
        nullptr,
        &hCuClasses,
        nullptr);
    if (cuStatus == ERROR_SUCCESS) {
        cuHr = RegisterComServerUnderRoot(hCuClasses, dllPath);
        if (SUCCEEDED(cuHr)) {
            cuHr = RegisterWithFilterMapper(hCuClasses);
            if (FAILED(cuHr)) {
                cuHr = RegisterCategoryInstanceDirect(hCuClasses);
            } else {
                EnsureCategoryDescription(hCuClasses);
            }
        }
        RegCloseKey(hCuClasses);
    } else {
        cuHr = HRESULT_FROM_WIN32(cuStatus);
    }

    BroadcastDeviceChange();
    return SUCCEEDED(lmHr) ? lmHr : cuHr;
}

extern "C" HRESULT __stdcall DllUnregisterServer() {
    const HRESULT coHr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool needUninit = SUCCEEDED(coHr);

    {
        vcam::ComPtr<IFilterMapper2> mapper;
        if (SUCCEEDED(CoCreateInstance(
                CLSID_FilterMapper2,
                nullptr,
                CLSCTX_INPROC_SERVER,
                IID_IFilterMapper2,
                reinterpret_cast<void**>(mapper.ReleaseAndGetAddressOf()))) &&
            mapper) {
            mapper->UnregisterFilter(
                &CLSID_VideoInputDeviceCategory,
                ::kVcamClsidString,
                CLSID_PhoneLinkVirtualCam);
        }
    }

    if (needUninit) {
        CoUninitialize();
    }

    HKEY hLmClasses = nullptr;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"Software\\Classes", 0, KEY_READ | KEY_WRITE, &hLmClasses) == ERROR_SUCCESS) {
        DeleteKeysUnderRoot(hLmClasses);
        RegCloseKey(hLmClasses);
    }

    HKEY hCuClasses = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Classes", 0, KEY_READ | KEY_WRITE, &hCuClasses) == ERROR_SUCCESS) {
        DeleteKeysUnderRoot(hCuClasses);
        RegCloseKey(hCuClasses);
    }

    BroadcastDeviceChange();
    return S_OK;
}
