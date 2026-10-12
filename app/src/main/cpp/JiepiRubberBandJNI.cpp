// jiepi Rubber Band JNI 封装（包名 com.jiepi.player）
// 暴露最小接口：create / destroy / process / available / retrieve /
// setTimeRatio / setPitchScale / getTimeRatio / getPitchScale / reset
// 输入输出均为 short 交错 PCM16；内部转换为 float 分通道交给 RubberBandStretcher。
#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include "RubberBandStretcher.h"

using namespace RubberBand;

static RubberBandStretcher *getHandle(JNIEnv *env, jobject obj) {
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) return nullptr;
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    env->DeleteLocalRef(cls);
    if (fid == nullptr) return nullptr;
    return reinterpret_cast<RubberBandStretcher *>(env->GetLongField(obj, fid));
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jiepi_player_RubberBandStream_nativeCreate(
        JNIEnv *env, jclass, jint sampleRate, jint channels) {
    try {
        RubberBandStretcher *st =
                new RubberBandStretcher(sampleRate, channels,
                                        RubberBandStretcher::OptionProcessRealTime,
                                        1.0, 1.0);
        return reinterpret_cast<jlong>(st);
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_jiepi_player_RubberBandStream_nativeDestroy(
        JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<RubberBandStretcher *>(handle);
}

JNIEXPORT void JNICALL
Java_com_jiepi_player_RubberBandStream_nativeSetTimeRatio(
        JNIEnv *, jclass, jlong handle, jdouble ratio) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    if (st) st->setTimeRatio(ratio);
}

JNIEXPORT void JNICALL
Java_com_jiepi_player_RubberBandStream_nativeSetPitchScale(
        JNIEnv *, jclass, jlong handle, jdouble scale) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    if (st) st->setPitchScale(scale);
}

JNIEXPORT jdouble JNICALL
Java_com_jiepi_player_RubberBandStream_nativeGetTimeRatio(
        JNIEnv *, jclass, jlong handle) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    return st ? st->getTimeRatio() : 1.0;
}

JNIEXPORT jdouble JNICALL
Java_com_jiepi_player_RubberBandStream_nativeGetPitchScale(
        JNIEnv *, jclass, jlong handle) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    return st ? st->getPitchScale() : 1.0;
}

JNIEXPORT void JNICALL
Java_com_jiepi_player_RubberBandStream_nativeProcess(
        JNIEnv *env, jclass, jlong handle, jshortArray inArray,
        jint frames, jboolean isFinal) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    if (!st || !inArray) return;
    jsize inLen = env->GetArrayLength(inArray);
    jshort *inRaw = env->GetShortArrayElements(inArray, nullptr);
    if (!inRaw) return;
    int ch = st->getChannelCount();
    int total = frames * ch;
    if (inLen < total) total = (int)inLen;
    if (total < frames * ch) frames = total / ch;
    // short 交错 -> float 分通道（真解交织：chBufs[c][f] = inRaw[f*ch + c]）
    std::vector<std::vector<float>> chBufs((size_t)ch, std::vector<float>(frames));
    for (int f = 0; f < frames; ++f) {
        for (int c = 0; c < ch; ++c) {
            chBufs[c][f] = (float)inRaw[f * ch + c] / 32768.0f;
        }
    }
    env->ReleaseShortArrayElements(inArray, inRaw, JNI_ABORT);
    std::vector<const float *> chPtrs((size_t)ch);
    for (int c = 0; c < ch; ++c) chPtrs[c] = chBufs[c].data();
    st->process(chPtrs.data(), frames, isFinal == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_jiepi_player_RubberBandStream_nativeAvailable(
        JNIEnv *, jclass, jlong handle) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    return st ? (jint)st->available() : 0;
}

JNIEXPORT jint JNICALL
Java_com_jiepi_player_RubberBandStream_nativeRetrieve(
        JNIEnv *env, jclass, jlong handle, jshortArray outArray,
        jint maxFrames) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    if (!st || !outArray) return 0;
    int ch = st->getChannelCount();
    jsize outLen = env->GetArrayLength(outArray);
    if ((jsize)(maxFrames * ch) > outLen) maxFrames = (int)(outLen / ch);
    if (maxFrames <= 0) return 0;
    std::vector<float> outBuf((size_t)maxFrames * ch);
    std::vector<float *> chPtrs((size_t)ch);
    for (int c = 0; c < ch; ++c) chPtrs[c] = outBuf.data() + (size_t)c * maxFrames;
    int got = st->retrieve(chPtrs.data(), maxFrames);
    if (got > 0) {
        jshort *outRaw = env->GetShortArrayElements(outArray, nullptr);
        if (outRaw) {
            // float 分通道 -> short 交错（真交织：outRaw[f*ch + c] = chBufs[c][f]）
            for (int f = 0; f < got; ++f) {
                for (int c = 0; c < ch; ++c) {
                    float v = outBuf[(size_t)c * maxFrames + f];
                    if (v > 1.0f) v = 1.0f; else if (v < -1.0f) v = -1.0f;
                    outRaw[f * ch + c] = (jshort)(v * 32767.0f);
                }
            }
            env->ReleaseShortArrayElements(outArray, outRaw, 0);
        }
    }
    return got;
}

JNIEXPORT void JNICALL
Java_com_jiepi_player_RubberBandStream_nativeReset(
        JNIEnv *, jclass, jlong handle) {
    RubberBandStretcher *st = reinterpret_cast<RubberBandStretcher *>(handle);
    if (st) st->reset();
}

} // extern "C"
