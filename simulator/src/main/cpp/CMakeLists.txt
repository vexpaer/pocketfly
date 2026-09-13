# Builds libpocketfly.so for Android (loaded by the :simulator Kotlin module).
# Host-side unit tests are built separately by scripts/run-native-tests.sh,
# which compiles the same core sources without the JNI layer.
cmake_minimum_required(VERSION 3.22.1)
project(pocketfly LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(pocketfly SHARED
        core/brain_format.cpp
        core/runtime.cpp
        jni/pocketfly_jni.cpp)

target_include_directories(pocketfly PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(pocketfly PRIVATE ${log-lib})

target_compile_options(pocketfly PRIVATE -Wall -Wextra)
