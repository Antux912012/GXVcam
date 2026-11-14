cmake_minimum_required(VERSION 3.22.1)
project(gxvcam_native C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(GXVCAM_CORE_SOURCES
    src/raw_unpacker.cpp
    src/IctcpToneMapper.cpp
    vulkan/BayerPyramidAligner.cpp
    motion/SyntheticEvsDeblur.cpp
    zoom/DrizzleSuperRes.cpp
)

# Native library built for Android
if(ANDROID)
    add_library(gxvcam_engine SHARED
        ${GXVCAM_CORE_SOURCES}
        src/jni_bridge.cpp
    )
    target_include_directories(gxvcam_engine PUBLIC include)
    target_compile_options(gxvcam_engine PRIVATE -Wall -Wextra -O3)

    find_library(log-lib log)
    find_library(android-lib android)
    find_library(vulkan-lib vulkan)
    target_link_libraries(gxvcam_engine ${log-lib} ${android-lib} ${vulkan-lib})
    # Android 15/16 16KB memory page size alignment requirement
    target_link_options(gxvcam_engine PRIVATE -Wl,-z,max-page-size=16384)

    # Compile Vulkan 1.3 GLSL shaders into SPIR-V via NDK glslc
    find_program(GLSLC_EXECUTABLE
        NAMES glslc
        HINTS
            "${ANDROID_NDK}/shader-tools/linux-x86_64"
            "${ANDROID_NDK}/shader-tools/darwin-x86_64"
            "${ANDROID_NDK}/shader-tools/windows-x86_64"
            "$ENV{ANDROID_NDK_HOME}/shader-tools/linux-x86_64"
    )
    if(GLSLC_EXECUTABLE)
        set(SHADER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/shaders")
        set(SPIRV_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/spirv")
        file(MAKE_DIRECTORY "${SPIRV_OUT_DIR}")

        set(GXVCAM_SHADERS
            unpack_raw10.comp
            bayer_pyramid_align.comp
            bayer_green_downsample.comp
            l1_tile_align_merge.comp
            anisotropic_demosaic.comp
            dark_channel_dehaze.comp
            ictcp_jnd_tonemap.frag
        )
        set(SPIRV_BINARIES "")
        foreach(SHADER_FILE ${GXVCAM_SHADERS})
            set(SRC_PATH "${SHADER_SRC_DIR}/${SHADER_FILE}")
            set(SPV_PATH "${SPIRV_OUT_DIR}/${SHADER_FILE}.spv")
            add_custom_command(
                OUTPUT "${SPV_PATH}"
                COMMAND "${GLSLC_EXECUTABLE}" --target-env=vulkan1.3 -O "${SRC_PATH}" -o "${SPV_PATH}"
                DEPENDS "${SRC_PATH}"
                COMMENT "Compiling Vulkan 1.3 SPIR-V shader: ${SHADER_FILE}"
                VERBATIM
            )
            list(APPEND SPIRV_BINARIES "${SPV_PATH}")
        endforeach()
        add_custom_target(gxvcam_spirv_shaders DEPENDS ${SPIRV_BINARIES})
        add_dependencies(gxvcam_engine gxvcam_spirv_shaders)
    else()
        message(WARNING "glslc not found in NDK shader-tools; skipping SPIR-V compilation")
    endif()
else()
    # Host build: unit tests run on the dev machine without a phone.
    add_library(gxvcam_engine STATIC
        ${GXVCAM_CORE_SOURCES}
    )
    target_include_directories(gxvcam_engine PUBLIC include)
    target_compile_options(gxvcam_engine PRIVATE -Wall -Wextra -O3)

    enable_testing()
    add_executable(test_raw_unpacker tests/test_raw_unpacker.cpp)
    target_link_libraries(test_raw_unpacker gxvcam_engine)
    add_test(NAME raw_unpacker COMMAND test_raw_unpacker)

    add_executable(test_signature27_engine tests/test_signature27_engine.cpp)
    target_link_libraries(test_signature27_engine gxvcam_engine)
    add_test(NAME signature27_engine COMMAND test_signature27_engine)
endif()
