# CMake build for the Windows natives, covering all three architectures in a
# single file. Driven by CI (see .github/workflows/natives-windows-cmake.yml).
# Does not replace the Gradle build under build.gradle.kts.
#
# Required cache variables:
#   FLATLAF_ARCH   one of: x86, x86_64, arm64
#   OUTPUT_DIR     directory the built .dll is copied into
#                  (typically flatlaf-core/src/main/resources/com/formdev/flatlaf/natives)
#
# Optional:
#   JAVA_HOME      JDK providing jni.h / jni_md.h (Win32 variant).
#                  Falls back to JAVA_HOME environment variable.
#
# Target arch is normally selected by CMake's generator platform
# (e.g. `cmake -A Win32|x64|ARM64`) or by activating the matching MSVC
# Developer Command Prompt before invoking CMake.

cmake_minimum_required(VERSION 3.16)

# Force the pre-3.15 CRT selection model: /MD (release) and /MDd (debug) are
# injected directly into CMAKE_CXX_FLAGS_RELEASE / ..._DEBUG. The newer
# (CMP0091 NEW) model selects the CRT via the MSVC_RUNTIME_LIBRARY property,
# but the Visual Studio generator in CMake 3.16 does not reliably emit
# /MD into the generated .vcxproj for this project — leaving vcruntime.lib
# unlinked and C++ exception symbols (__CxxFrameHandler3/4, __std_terminate)
# unresolved. Must be set before project().
cmake_policy(SET CMP0091 OLD)

project(flatlaf-natives-windows LANGUAGES CXX)

if(NOT DEFINED FLATLAF_ARCH)
    message(FATAL_ERROR "FLATLAF_ARCH must be set (x86, x86_64, arm64)")
endif()
if(NOT FLATLAF_ARCH MATCHES "^(x86|x86_64|arm64)$")
    message(FATAL_ERROR "Unsupported FLATLAF_ARCH='${FLATLAF_ARCH}'")
endif()
if(NOT DEFINED OUTPUT_DIR)
    message(FATAL_ERROR "OUTPUT_DIR must be set")
endif()

if(NOT DEFINED JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(JAVA_HOME $ENV{JAVA_HOME})
    else()
        message(FATAL_ERROR "JAVA_HOME must be set")
    endif()
endif()

set(SOURCES
    src/main/cpp/ApiVersion.cpp
    src/main/cpp/FlatWndProc.cpp
    src/main/cpp/HWNDMap.cpp
    src/main/cpp/JNIUtils.cpp
    src/main/cpp/Registry.cpp
    src/main/cpp/Runtime.cpp
    src/main/cpp/WinFileChooser.cpp
    src/main/cpp/WinMessageDialog.cpp
    src/main/cpp/WinWrapper.cpp
)

set(LIB_BASENAME "flatlaf-windows-${FLATLAF_ARCH}")
add_library(${LIB_BASENAME} SHARED ${SOURCES})

set_target_properties(${LIB_BASENAME} PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
    # Windows DLLs carry no "lib" prefix and use .dll, not .so/.dylib.
    PREFIX ""
    SUFFIX ".dll"
    OUTPUT_NAME "${LIB_BASENAME}"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
)

target_include_directories(${LIB_BASENAME} PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/win32"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/headers"
)

target_compile_definitions(${LIB_BASENAME} PRIVATE UNICODE _UNICODE)

if(MSVC)
    # Match the Gradle build's MSVC flags.
    target_compile_options(${LIB_BASENAME} PRIVATE /O2 /GS-)
else()
    target_compile_options(${LIB_BASENAME} PRIVATE -O2)
endif()

target_link_libraries(${LIB_BASENAME} PRIVATE
    User32 Gdi32 shell32 AdvAPI32 Kernel32 Dwmapi Ole32 uuid
)

add_custom_command(TARGET ${LIB_BASENAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${OUTPUT_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy
        "$<TARGET_FILE:${LIB_BASENAME}>"
        "${OUTPUT_DIR}/${LIB_BASENAME}.dll"
    VERBATIM
)
