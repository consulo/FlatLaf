# CMake build for the macOS natives. Each invocation builds a single-arch
# .dylib (not a universal binary) — the matrix in
# .github/workflows/natives-macos-cmake.yml drives arm64 and x86_64
# builds separately.
#
# Required cache variables:
#   FLATLAF_ARCH   one of: arm64, x86_64
#   OUTPUT_DIR     directory the built .dylib is copied into
#                  (typically flatlaf-core/src/main/resources/com/formdev/flatlaf/natives)
#
# Optional:
#   JAVA_HOME      JDK providing jni.h and darwin/jni_md.h.
#                  Falls back to JAVA_HOME environment variable.

cmake_minimum_required(VERSION 3.16)

if(NOT DEFINED FLATLAF_ARCH)
    message(FATAL_ERROR "FLATLAF_ARCH must be set (arm64 or x86_64)")
endif()
if(NOT FLATLAF_ARCH MATCHES "^(arm64|x86_64)$")
    message(FATAL_ERROR "Unsupported FLATLAF_ARCH='${FLATLAF_ARCH}'")
endif()

# CMAKE_OSX_* must be set BEFORE project(). These drive clang's -arch and
# -mmacosx-version-min flags directly.
set(CMAKE_OSX_ARCHITECTURES "${FLATLAF_ARCH}" CACHE STRING "" FORCE)
if(FLATLAF_ARCH STREQUAL "arm64")
    set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "" FORCE)
else()
    set(CMAKE_OSX_DEPLOYMENT_TARGET "10.14" CACHE STRING "" FORCE)
endif()

project(flatlaf-natives-macos LANGUAGES CXX OBJCXX)

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
    src/main/objcpp/ApiVersion.mm
    src/main/objcpp/JNFRunLoop.mm
    src/main/objcpp/JNIUtils.mm
    src/main/objcpp/MacFileChooser.mm
    src/main/objcpp/MacMessageDialog.mm
    src/main/objcpp/MacWindow.mm
)

# Mark all .mm sources as Objective-C++ so clang compiles them correctly.
set_source_files_properties(${SOURCES} PROPERTIES LANGUAGE OBJCXX)

set(LIB_BASENAME "flatlaf-macos-${FLATLAF_ARCH}")
add_library(${LIB_BASENAME} SHARED ${SOURCES})

set_target_properties(${LIB_BASENAME} PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
    PREFIX "lib"
    SUFFIX ".dylib"
    OUTPUT_NAME "${LIB_BASENAME}"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
)

target_include_directories(${LIB_BASENAME} PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/darwin"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/headers"
)

target_compile_options(${LIB_BASENAME} PRIVATE
    -fvisibility=hidden
    -O3
)

target_link_libraries(${LIB_BASENAME} PRIVATE
    "-lobjc"
    "-framework Cocoa"
)

add_custom_command(TARGET ${LIB_BASENAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${OUTPUT_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy
        "$<TARGET_FILE:${LIB_BASENAME}>"
        "${OUTPUT_DIR}/lib${LIB_BASENAME}.dylib"
    VERBATIM
)
