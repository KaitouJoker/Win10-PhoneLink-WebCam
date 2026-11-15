cmake_minimum_required(VERSION 3.20)
project(vcam-dshow LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(vcam-dshow SHARED
    dllmain.cpp
    vcam_filter.cpp
    shm_reader.cpp
    frame_convert.cpp
    vcam-dshow.def
    resource.rc
)

target_include_directories(vcam-dshow PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/../shared"
)

target_compile_definitions(vcam-dshow PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

if(MSVC)
    set_property(TARGET vcam-dshow PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    target_compile_options(vcam-dshow PRIVATE /W4 /EHsc)
endif()

target_link_libraries(vcam-dshow PRIVATE
    strmiids
    quartz
    ole32
    oleaut32
    advapi32
    shlwapi
    uuid
)

set_target_properties(vcam-dshow PROPERTIES
    OUTPUT_NAME "vcam-dshow"
    PREFIX ""
)
