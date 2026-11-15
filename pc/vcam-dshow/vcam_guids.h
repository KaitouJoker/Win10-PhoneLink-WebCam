#pragma once

#include <guiddef.h>

// {8E4C7A21-5B93-4F8E-9C12-6A4D3E8F1022}
inline constexpr GUID CLSID_PhoneLinkVirtualCam = {
    0x8e4c7a21, 0x5b93, 0x4f8e, { 0x9c, 0x12, 0x6a, 0x4d, 0x3e, 0x8f, 0x10, 0x22 }
};

inline constexpr wchar_t kVcamClsidString[] = L"{8E4C7A21-5B93-4F8E-9C12-6A4D3E8F1022}";
inline constexpr wchar_t kVcamFriendlyName[] = L"Win10 PhoneLink Virtual Camera";
inline constexpr wchar_t kVcamPinName[] = L"Capture";
inline constexpr wchar_t kVcamPinId[] = L"Capture";
