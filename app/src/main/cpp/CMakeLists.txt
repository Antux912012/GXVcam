cmake_minimum_required(VERSION 3.22.1)
project(gxvcam_native C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Native library built for Android
if(ANDROID)
    add_library(gxvcam_engine SHARED
        src/raw_unpacker.cpp
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
else()
    # Host build: unit tests run on the dev machine without a phone.
    add_library(gxvcam_engine STATIC
        src/raw_unpacker.cpp
    )
    target_include_directories(gxvcam_engine PUBLIC include)
    target_compile_options(gxvcam_engine PRIVATE -Wall -Wextra -O3)

    enable_testing()
    add_executable(test_raw_unpacker tests/test_raw_unpacker.cpp)
    target_link_libraries(test_raw_unpacker gxvcam_engine)
    add_test(NAME raw_unpacker COMMAND test_raw_unpacker)
endif()
