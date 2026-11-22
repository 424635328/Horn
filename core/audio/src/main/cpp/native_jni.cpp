#include "native_engine.h"

#include <jni.h>

#include <algorithm>

namespace {
NativeHornEngine* engine(jlong handle) {
    return reinterpret_cast<NativeHornEngine*>(handle);
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeCreate(JNIEnv*, jobject) {
    auto* created = new NativeHornEngine();
    return reinterpret_cast<jlong>(created);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativePrepare(JNIEnv*, jobject,
                                                                    jlong handle) {
    if (handle == 0) return JNI_FALSE;
    return engine(handle)->prepare() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeStart(JNIEnv*, jobject,
                                                                  jlong handle) {
    if (handle == 0) return JNI_FALSE;
    return engine(handle)->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeStop(JNIEnv*, jobject,
                                                                 jlong handle) {
    if (handle != 0) engine(handle)->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativePanic(JNIEnv*, jobject,
                                                                  jlong handle) {
    if (handle != 0) engine(handle)->panic();
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeRelease(JNIEnv*, jobject,
                                                                    jlong handle) {
    if (handle == 0) return;
    auto* instance = engine(handle);
    instance->release();
    delete instance;
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeSetType(JNIEnv*, jobject,
                                                                    jlong handle,
                                                                    jint type) {
    if (handle != 0) engine(handle)->setType(type);
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeSetExpression(JNIEnv*, jobject,
                                                                          jlong handle,
                                                                          jfloat expression) {
    if (handle != 0) engine(handle)->setExpression(expression);
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeSetSpace(JNIEnv*, jobject,
                                                                     jlong handle, jint space) {
    if (handle != 0) engine(handle)->setSpace(space);
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeSetProtection(JNIEnv*, jobject,
                                                                          jlong handle,
                                                                          jboolean enabled) {
    if (handle != 0) engine(handle)->setProtection(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeSetOutputMode(JNIEnv*, jobject,
                                                                          jlong handle,
                                                                          jint mode) {
    if (handle != 0) engine(handle)->setOutputMode(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeSetCustomProfile(
    JNIEnv*, jobject, jlong handle, jfloat primary, jfloat ratio, jboolean dual,
    jfloat richness, jfloat attackMs, jfloat releaseMs, jfloat saturation) {
    if (handle != 0) {
        engine(handle)->setCustomProfile(primary, ratio, dual == JNI_TRUE, richness, attackMs,
                                         releaseMs, saturation);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeReadTelemetry(
    JNIEnv* env, jobject, jlong handle, jfloatArray destination) {
    if (handle == 0 || destination == nullptr) return 0;
    const jsize length = env->GetArrayLength(destination);
    if (length < 9) return 0;
    float values[9] = {};
    const int written = engine(handle)->readTelemetry(values, 9);
    if (written > 0) {
        env->SetFloatArrayRegion(destination, 0, written, values);
    }
    return written;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeReadWaveform(
    JNIEnv* env, jobject, jlong handle, jfloatArray destination) {
    if (handle == 0 || destination == nullptr) return 0;
    const jsize length = env->GetArrayLength(destination);
    if (length <= 0) return 0;
    float* values = env->GetFloatArrayElements(destination, nullptr);
    if (values == nullptr) return 0;
    const int written = engine(handle)->readWaveform(values, length);
    env->ReleaseFloatArrayElements(destination, values, 0);
    return written;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_industrial_horn_audio_OboeHornAudioEngine_nativeReadWaveformStereo(
    JNIEnv* env, jobject, jlong handle, jfloatArray left, jfloatArray right) {
    if (handle == 0 || left == nullptr || right == nullptr) return 0;
    const jsize leftLength = env->GetArrayLength(left);
    const jsize rightLength = env->GetArrayLength(right);
    const int capacity = static_cast<int>(std::min(leftLength, rightLength));
    if (capacity <= 0) return 0;
    float* leftValues = env->GetFloatArrayElements(left, nullptr);
    if (leftValues == nullptr) return 0;
    float* rightValues = env->GetFloatArrayElements(right, nullptr);
    if (rightValues == nullptr) {
        env->ReleaseFloatArrayElements(left, leftValues, JNI_ABORT);
        return 0;
    }
    const int written = engine(handle)->readWaveformStereo(leftValues, rightValues, capacity);
    env->ReleaseFloatArrayElements(left, leftValues, 0);
    env->ReleaseFloatArrayElements(right, rightValues, 0);
    return written;
}
