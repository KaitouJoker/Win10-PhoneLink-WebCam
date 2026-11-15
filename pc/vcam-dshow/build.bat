@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%" || exit /b 1

set "DIST_DIR=..\..\dist\Portable-Win10-PhoneLink-WebCam"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

call :build_arch x64 "build\x64-release" "D:\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" "vcam-dshow"
if errorlevel 1 (
    popd
    exit /b 1
)

call :build_arch x86 "build\x86-release" "D:\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars32.bat" "vcam-dshow32"
if errorlevel 1 (
    popd
    exit /b 1
)

echo [OK] Built vcam-dshow.dll (x64) and vcam-dshow32.dll (x86) into "%DIST_DIR%"
popd
exit /b 0

:build_arch
setlocal
set "ARCH=%~1"
set "BUILD_DIR=%~2"
set "VCVARS=%~3"
set "OUT_NAME=%~4"
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
call "%VCVARS%"
if errorlevel 1 (
    endlocal
    exit /b 1
)
rc /nologo /fo "%BUILD_DIR%\resource.res" resource.rc
if errorlevel 1 (
    endlocal
    exit /b 1
)
cl /nologo /std:c++20 /O2 /MT /W4 /WX- /EHsc /DUNICODE /D_UNICODE /DNOMINMAX /DWIN32_LEAN_AND_MEAN ^
    /I. /I..\shared ^
    /Fo"%BUILD_DIR%/" /Fd"%BUILD_DIR%\%OUT_NAME%.pdb" ^
    /LD dllmain.cpp vcam_filter.cpp shm_reader.cpp frame_convert.cpp ^
    "%BUILD_DIR%\resource.res" ^
    /link /DEF:vcam-dshow.def /IMPLIB:"%BUILD_DIR%\%OUT_NAME%.lib" /OUT:"%BUILD_DIR%\%OUT_NAME%.dll" ^
    strmiids.lib quartz.lib ole32.lib oleaut32.lib advapi32.lib shlwapi.lib uuid.lib user32.lib
if errorlevel 1 (
    endlocal
    exit /b 1
)
copy /Y "%BUILD_DIR%\%OUT_NAME%.dll" "%DIST_DIR%\%OUT_NAME%.dll" >nul
if errorlevel 1 (
    endlocal
    exit /b 1
)
echo [OK] Built %OUT_NAME%.dll (%ARCH%)
endlocal
exit /b 0
