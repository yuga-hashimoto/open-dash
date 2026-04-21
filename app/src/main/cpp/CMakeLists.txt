cmake_minimum_required(VERSION 3.22)
project(llama_jni)

set(CMAKE_CXX_STANDARD 17)

# Let llama.cpp / whisper.cpp handle ARM optimizations via their own Android
# detection. Both libraries share the same ggml backend flags; setting them
# once at top level keeps the two subprojects in sync.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# P14.1 groundwork: build whisper.cpp alongside llama.cpp when the submodule
# is present. The EXISTS guard keeps local dev builds working if someone
# forgets `git submodule update --init` — CI now sets `submodules: recursive`
# so every PR build has both trees. No JNI binding is wired up yet (item 3
# from docs/phase-16-native-plan.md); this produces the `whisper` static
# library target that future whisper_jni.cpp will link against.
# EXCLUDE_FROM_ALL keeps `whisper` off the default build set — only code
# that explicitly target_link_libraries(... whisper ...) will pull it in.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/CMakeLists.txt)
    add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)
else()
    message(STATUS "whisper.cpp submodule not initialised; STT native backend disabled")
endif()

add_library(llama_jni SHARED llama_jni.cpp)

find_library(log-lib log)
target_link_libraries(llama_jni llama ${log-lib})
target_include_directories(llama_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)
