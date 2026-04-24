# Alternative build for Linux natives, covering all supported architectures
# in a single CMake project. Used by CI
# (see .github/workflows/natives-linux-cmake.yml).
#
# The canonical Gradle build (flatlaf-native-linux/build.gradle.kts) is still
# used for x86_64 / aarch64 on native GitHub runners; this CMake project is
# the only path for x86, riscv64, and loongarch64.
#
# Required cache variables (pass via -D...):
#   FLATLAF_ARCH   one of: x86_64, x86, arm64, riscv64, loongarch64
#                  (drives compiler selection AND output file name)
#   OUTPUT_DIR     directory the built .so is copied into
#                  (typically flatlaf-core/src/main/resources/com/formdev/flatlaf/natives)
#   JAWT_LIB_DIR   directory containing target-arch libjawt.so
#
# Optional:
#   JAVA_HOME      JDK supplying jni.h / jni_md.h (host JDK works;
#                  jni_md.h under include/linux is arch-agnostic on Linux).
#                  Falls back to the JAVA_HOME environment variable.

cmake_minimum_required(VERSION 3.16)

# ---- Arch selection (must happen BEFORE project() to take effect) ----------

if(NOT DEFINED FLATLAF_ARCH)
    message(FATAL_ERROR
        "FLATLAF_ARCH is not set. "
        "Use -DFLATLAF_ARCH=<x86_64|x86|arm64|riscv64|loongarch64>")
endif()

# Per-arch configuration table.
# Fields per row: <FLATLAF_ARCH>  <triplet>  <processor>  <cross?>  <extra-flags>
set(_arch_table
    "x86_64      x86_64-linux-gnu       x86_64       NO   "
    "x86         i386-linux-gnu         i686         NO   -m32"
    "arm64       aarch64-linux-gnu      aarch64      YES  "
    "riscv64     riscv64-linux-gnu      riscv64      YES  "
    "loongarch64 loongarch64-linux-gnu  loongarch64  YES  "
)

set(_match "")
foreach(_row IN LISTS _arch_table)
    separate_arguments(_cols UNIX_COMMAND "${_row}")
    list(GET _cols 0 _a)
    if(_a STREQUAL "${FLATLAF_ARCH}")
        set(_match "${_row}")
        break()
    endif()
endforeach()

if(_match STREQUAL "")
    message(FATAL_ERROR "Unsupported FLATLAF_ARCH='${FLATLAF_ARCH}'")
endif()

separate_arguments(_cols UNIX_COMMAND "${_match}")
list(GET _cols 1 FLATLAF_TRIPLET)
list(GET _cols 2 FLATLAF_PROCESSOR)
list(GET _cols 3 FLATLAF_IS_CROSS)
# remaining cols (if any) are extra compile/link flags
set(FLATLAF_EXTRA_FLAGS "")
list(LENGTH _cols _n)
if(_n GREATER 4)
    foreach(_i RANGE 4 ${_n})
        if(_i LESS _n)
            list(GET _cols ${_i} _f)
            list(APPEND FLATLAF_EXTRA_FLAGS "${_f}")
        endif()
    endforeach()
endif()

# Set the toolchain-related variables *before* project(). Setting
# CMAKE_SYSTEM_NAME here triggers CMake's cross-compile mode, which is what we
# want for every arch except the native host.
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR "${FLATLAF_PROCESSOR}")

if(FLATLAF_IS_CROSS STREQUAL "YES")
    set(CMAKE_C_COMPILER   "${FLATLAF_TRIPLET}-gcc")
    set(CMAKE_CXX_COMPILER "${FLATLAF_TRIPLET}-g++")
    set(CMAKE_FIND_ROOT_PATH /usr/${FLATLAF_TRIPLET} /usr/lib/${FLATLAF_TRIPLET})
    set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
    set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
    set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
else()
    # Native x86_64 and x86 (the latter uses the host compiler with -m32).
    set(CMAKE_C_COMPILER   gcc)
    set(CMAKE_CXX_COMPILER g++)
endif()

# Optional isolated sysroot. Used when installing target-arch packages via
# dpkg multiarch would clash with the host's package versions (e.g. loong64
# against a sid amd64 container). Files live under FLATLAF_SYSROOT instead
# of on the host filesystem.
if(DEFINED FLATLAF_SYSROOT)
    set(CMAKE_SYSROOT "${FLATLAF_SYSROOT}")
    set(ENV{PKG_CONFIG_SYSROOT_DIR} "${FLATLAF_SYSROOT}")
    set(ENV{PKG_CONFIG_LIBDIR}
        "${FLATLAF_SYSROOT}/usr/lib/${FLATLAF_TRIPLET}/pkgconfig:${FLATLAF_SYSROOT}/usr/share/pkgconfig")
else()
    # pkg-config: front-load the target arch's multiarch .pc files so GTK
    # headers and libs resolve for the target, then fall back to the
    # non-multiarch layout used by distros like Arch Linux.
    set(ENV{PKG_CONFIG_LIBDIR}
        "/usr/lib/${FLATLAF_TRIPLET}/pkgconfig:/usr/lib/pkgconfig:/usr/share/pkgconfig")
    set(ENV{PKG_CONFIG_SYSROOT_DIR} "")
endif()

project(flatlaf-natives-linux LANGUAGES CXX)

# ---- Inputs other than FLATLAF_ARCH ---------------------------------------

if(NOT DEFINED OUTPUT_DIR)
    message(FATAL_ERROR "OUTPUT_DIR is not set")
endif()
if(NOT DEFINED JAWT_LIB_DIR)
    message(FATAL_ERROR "JAWT_LIB_DIR is not set (must contain target-arch libjawt.so)")
endif()
if(NOT DEFINED JAVA_HOME)
    if(DEFINED ENV{JAVA_HOME})
        set(JAVA_HOME $ENV{JAVA_HOME})
    else()
        message(FATAL_ERROR "JAVA_HOME is not set")
    endif()
endif()
if(NOT EXISTS "${JAWT_LIB_DIR}/libjawt.so")
    message(FATAL_ERROR "libjawt.so not found in JAWT_LIB_DIR='${JAWT_LIB_DIR}'")
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(GTK3 REQUIRED gtk+-3.0)

# ---- Target ---------------------------------------------------------------

set(SOURCES
    src/main/cpp/ApiVersion.cpp
    src/main/cpp/GtkFileChooser.cpp
    src/main/cpp/GtkMessageDialog.cpp
    src/main/cpp/JNIUtils.cpp
    src/main/cpp/X11WmUtils.cpp
)

set(LIB_BASENAME "flatlaf-linux-${FLATLAF_ARCH}")
add_library(${LIB_BASENAME} SHARED ${SOURCES})

set_target_properties(${LIB_BASENAME} PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
    PREFIX "lib"
    OUTPUT_NAME "${LIB_BASENAME}"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
)

target_include_directories(${LIB_BASENAME} PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/linux"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/headers"
    ${GTK3_INCLUDE_DIRS}
)

target_compile_options(${LIB_BASENAME} PRIVATE
    -fvisibility=hidden
    -O3
    ${FLATLAF_EXTRA_FLAGS}
    ${GTK3_CFLAGS_OTHER}
)

target_link_directories(${LIB_BASENAME} PRIVATE
    "${JAWT_LIB_DIR}"
    ${GTK3_LIBRARY_DIRS}
)

target_link_libraries(${LIB_BASENAME} PRIVATE
    jawt
    ${GTK3_LIBRARIES}
)

target_link_options(${LIB_BASENAME} PRIVATE
    ${FLATLAF_EXTRA_FLAGS}
    "-Wl,-soname,lib${LIB_BASENAME}.so"
)

add_custom_command(TARGET ${LIB_BASENAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${OUTPUT_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy
        "$<TARGET_FILE:${LIB_BASENAME}>"
        "${OUTPUT_DIR}/lib${LIB_BASENAME}.so"
    VERBATIM
)
