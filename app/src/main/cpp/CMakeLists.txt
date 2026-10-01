# SPDX-License-Identifier: Apache-2.0
cmake_minimum_required(VERSION 3.22.1)

# JNI bridge for the Mokya IME. Built two ways from this one file:
#   - by the Android Gradle plugin (externalNativeBuild) for each device ABI;
#   - on the host by the :mie-engine Gradle module, against the local JDK's
#     JNI headers, so the JVM unit tests exercise the real bridge.
project(mokyaime_jni CXX)

# The static `mie` library is linked into a shared object, so it must be
# position independent (the NDK defaults to PIC; host toolchains do not).
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Only JNI_OnLoad needs to be exported; natives are bound by RegisterNatives.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# libmie (MIT) is a git submodule. As a subdirectory it builds only the `mie`
# static library: no REPL, unit tests or GoogleTest download.
add_subdirectory(libmie)

add_library(mokyaime_jni SHARED mie_jni.cpp)
target_compile_features(mokyaime_jni PRIVATE cxx_std_17)
target_link_libraries(mokyaime_jni PRIVATE mie)

if(ANDROID)
    # 16 KB page-size compatible ELF segments (required for Android 15+
    # devices with 16 KB pages; harmless elsewhere).
    target_link_options(mokyaime_jni PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Host build: JNI headers come from the JDK. MOKYA_JAVA_HOME is passed by
    # the :mie-engine Gradle task; JAVA_HOME is the fallback.
    set(MOKYA_JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "JDK used for the host JNI build")
    find_path(MOKYA_JNI_INCLUDE jni.h
              HINTS "${MOKYA_JAVA_HOME}/include" NO_DEFAULT_PATH REQUIRED)
    find_path(MOKYA_JNI_MD_INCLUDE jni_md.h
              HINTS "${MOKYA_JNI_INCLUDE}/linux" "${MOKYA_JNI_INCLUDE}/darwin"
                    "${MOKYA_JNI_INCLUDE}/win32"
              NO_DEFAULT_PATH REQUIRED)
    target_include_directories(mokyaime_jni PRIVATE
        "${MOKYA_JNI_INCLUDE}" "${MOKYA_JNI_MD_INCLUDE}")
    # Multi-config generators (Visual Studio) would put the library in a
    # Debug/ subdirectory; a generator expression keeps it where the tests
    # look (java.library.path is the build directory).
    set_target_properties(mokyaime_jni PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>"
        RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")
    if(MSVC)
        # The sources and libmie's headers are UTF-8 without a BOM; MSVC
        # would otherwise read them in the system code page. libmie sets
        # this for its own targets only.
        target_compile_options(mokyaime_jni PRIVATE /utf-8)
    endif()
endif()
