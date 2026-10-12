cmake_minimum_required(VERSION 3.22.1)
project(rubberband_jiepi)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# 仅编译 arm64-v8a（由 abiFilters 控制）；-O3 启用、静态链 libc++_static
set(CMAKE_C_FLAGS_RELEASE "-O3")
set(CMAKE_CXX_FLAGS_RELEASE "-O3")

add_library(rubberband SHARED
        rubberband/src/rubberband-c.cpp
        rubberband/src/RubberBandStretcher.cpp
        rubberband/src/RubberBandLiveShifter.cpp
        rubberband/src/common/Allocators.cpp
        rubberband/src/common/BQResampler.cpp
        rubberband/src/common/FFT.cpp
        rubberband/src/common/Log.cpp
        rubberband/src/common/mathmisc.cpp
        rubberband/src/common/Profiler.cpp
        rubberband/src/common/Resampler.cpp
        rubberband/src/common/StretchCalculator.cpp
        rubberband/src/common/sysutils.cpp
        rubberband/src/common/Thread.cpp
        rubberband/src/common/VectorOpsComplex.cpp
        rubberband/src/faster/AudioCurveCalculator.cpp
        rubberband/src/faster/CompoundAudioCurve.cpp
        rubberband/src/faster/HighFrequencyAudioCurve.cpp
        rubberband/src/faster/PercussiveAudioCurve.cpp
        rubberband/src/faster/R2Stretcher.cpp
        rubberband/src/faster/SilentAudioCurve.cpp
        rubberband/src/faster/StretcherChannelData.cpp
        rubberband/src/faster/StretcherProcess.cpp
        rubberband/src/finer/R3LiveShifter.cpp
        rubberband/src/finer/R3Stretcher.cpp
        rubberband/src/ext/kissfft/kiss_fft.c
        rubberband/src/ext/kissfft/kiss_fftr.c
        rubberband/src/ext/speex/resample.c
        JiepiRubberBandJNI.cpp)

target_include_directories(rubberband PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/rubberband
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src/common
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src/faster
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src/finer
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src/ext/kissfft
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src/ext/speex
        ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src/ext/pommier)

# Rubber Band 需要显式选择各后端，否则 #error 中断：
# USE_PTHREADS      Android bionic 提供 pthread
# HAVE_KISSFFT      内置 kissfft（float 模式，src/ext/kissfft）
# USE_SPEEX         内置 speex resample（src/ext/speex，头文件已 define RANDOM_PREFIX=rubberband）
# HAVE_POSIX_MEMALIGN  bionic 提供 posix_memalign，Allocators.h 用其做对齐分配
target_compile_definitions(rubberband PRIVATE
        USE_PTHREADS
        HAVE_KISSFFT
        USE_SPEEX
        HAVE_POSIX_MEMALIGN)

target_link_libraries(rubberband PRIVATE log)
