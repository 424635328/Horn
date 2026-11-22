#include "native_engine.h"

#include <jni.h>

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
    if (length < 6) return 0;
    float values[6] = {};
    engine(handle)->readTelemetry(values, 6);
    env->SetFloatArrayRegion(destination, 0, 6, values);
    return 6;
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
