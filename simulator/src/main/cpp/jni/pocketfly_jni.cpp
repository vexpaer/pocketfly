// JNI bridge between the Kotlin PocketFlyRuntime facade and the C++ core.
// Each Java instance owns one pocketfly::Runtime via a raw handle (jlong).

#include <jni.h>

#include <string>

#include "core/runtime.h"

namespace {

inline pocketfly::Runtime* asRuntime(jlong handle) {
    return reinterpret_cast<pocketfly::Runtime*>(static_cast<uintptr_t>(handle));
}

inline jlong asHandle(pocketfly::Runtime* runtime) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(runtime));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeCreate(JNIEnv*, jobject) {
    return asHandle(new pocketfly::Runtime());
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete asRuntime(handle);
}

JNIEXPORT jboolean JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeLoad(
        JNIEnv* env, jobject, jlong handle, jstring dirPath,
        jint expectedNeurons, jlong expectedEdges, jint expectedGroups, jfloat weightScale) {
    auto* runtime = asRuntime(handle);
    if (runtime == nullptr) return JNI_FALSE;
    const char* dir = env->GetStringUTFChars(dirPath, nullptr);
    if (dir == nullptr) return JNI_FALSE;
    std::string error;
    const jboolean ok = runtime->loadFromDirectory(dir, expectedNeurons,
                                                   static_cast<uint64_t>(expectedEdges),
                                                   expectedGroups, weightScale, &error)
                            ? JNI_TRUE
                            : JNI_FALSE;
    env->ReleaseStringUTFChars(dirPath, dir);
    return ok;
}

JNIEXPORT jstring JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeLastError(JNIEnv* env, jobject,
                                                              jlong handle) {
    const std::string& err = asRuntime(handle)->lastError();
    return env->NewStringUTF(err.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeReset(JNIEnv*, jobject, jlong handle) {
    asRuntime(handle)->reset();
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeStep(JNIEnv*, jobject, jlong handle,
                                                         jint steps) {
    asRuntime(handle)->step(static_cast<uint32_t>(steps));
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeSetInput(JNIEnv*, jobject, jlong handle,
                                                             jint neuronId, jfloat value) {
    asRuntime(handle)->setInput(static_cast<uint32_t>(neuronId), value);
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeSetInputGroup(
        JNIEnv* env, jobject, jlong handle, jint groupId, jfloatArray values) {
    const jsize len = env->GetArrayLength(values);
    std::vector<float> tmp(static_cast<size_t>(len));
    env->GetFloatArrayRegion(values, 0, len, tmp.data());
    asRuntime(handle)->setInputGroup(static_cast<uint16_t>(groupId), tmp.data(),
                                     static_cast<uint32_t>(len));
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeClearInputs(JNIEnv*, jobject, jlong handle) {
    asRuntime(handle)->clearInputs();
}

JNIEXPORT jfloat JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeGetActivity(JNIEnv*, jobject, jlong handle,
                                                                jint neuronId) {
    return asRuntime(handle)->getActivity(static_cast<uint32_t>(neuronId));
}

JNIEXPORT jfloat JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeGetPotential(JNIEnv*, jobject, jlong handle,
                                                                 jint neuronId) {
    return asRuntime(handle)->getPotential(static_cast<uint32_t>(neuronId));
}

JNIEXPORT jfloat JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeGetGroupActivity(JNIEnv*, jobject,
                                                                     jlong handle, jint groupId) {
    return asRuntime(handle)->getGroupMeanActivity(static_cast<uint16_t>(groupId));
}

JNIEXPORT jint JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeGetGroupSize(JNIEnv*, jobject, jlong handle,
                                                                 jint groupId) {
    return static_cast<jint>(asRuntime(handle)->getGroupSize(static_cast<uint16_t>(groupId)));
}

JNIEXPORT jint JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeGetActiveCount(JNIEnv*, jobject,
                                                                   jlong handle) {
    return static_cast<jint>(asRuntime(handle)->getActiveNeuronCount());
}

// [neuronCount, edgeCount, activeNeurons, ablatedNeurons, stepsPerSec,
//  lastStepMs, meanStepMs, totalSteps, approxMemoryBytes]
JNIEXPORT jdoubleArray JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeStats(JNIEnv* env, jobject, jlong handle) {
    const pocketfly::SimulationStats s = asRuntime(handle)->getStats();
    const jsize n = 9;
    jdoubleArray out = env->NewDoubleArray(n);
    const jdouble values[n] = {
            static_cast<jdouble>(s.neuronCount), static_cast<jdouble>(s.edgeCount),
            static_cast<jdouble>(s.activeNeurons), static_cast<jdouble>(s.ablatedNeurons),
            s.stepsPerSecond, s.lastStepMs, s.meanStepMs, static_cast<jdouble>(s.totalSteps),
            s.approxMemoryBytes};
    env->SetDoubleArrayRegion(out, 0, n, values);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeSetNeuronEnabled(
        JNIEnv*, jobject, jlong handle, jint neuronId, jboolean enabled) {
    return asRuntime(handle)->setNeuronEnabled(static_cast<uint32_t>(neuronId), enabled == JNI_TRUE)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeSetGroupEnabled(JNIEnv*, jobject, jlong handle,
                                                                    jint groupId,
                                                                    jboolean enabled) {
    asRuntime(handle)->setGroupEnabled(static_cast<uint16_t>(groupId), enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeClearAblations(JNIEnv*, jobject,
                                                                   jlong handle) {
    asRuntime(handle)->clearAblations();
}

JNIEXPORT void JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeSetParams(
        JNIEnv*, jobject, jlong handle, jfloat decay, jfloat threshold, jfloat gain,
        jfloat noise, jfloat activationDecay, jboolean refractory) {
    pocketfly::RuntimeParams p;
    p.decay = decay;
    p.threshold = threshold;
    p.gain = gain;
    p.noise = noise;
    p.activationDecay = activationDecay;
    p.refractory = refractory == JNI_TRUE;
    asRuntime(handle)->setParams(p);
}

// ints: [id, group, type, region, side, flags, inDegree, outDegree, enabled]
JNIEXPORT jintArray JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeGetNeuronInfoInts(JNIEnv* env, jobject,
                                                                      jlong handle,
                                                                      jint neuronId) {
    const pocketfly::NeuronInfo info =
            asRuntime(handle)->getNeuronInfo(static_cast<uint32_t>(neuronId));
    const jsize n = 9;
    jintArray out = env->NewIntArray(n);
    const jint values[n] = {static_cast<jint>(info.id), info.group, info.type, info.region,
                            info.side, info.flags, static_cast<jint>(info.inDegree),
                            static_cast<jint>(info.outDegree), info.enabled ? 1 : 0};
    env->SetIntArrayRegion(out, 0, n, values);
    return out;
}

// floats: [activity, potential]
JNIEXPORT jfloatArray JNICALL
Java_io_github_pocketfly_sim_PocketFlyRuntime_nativeGetNeuronInfoFloats(JNIEnv* env, jobject,
                                                                        jlong handle,
                                                                        jint neuronId) {
    const pocketfly::NeuronInfo info =
            asRuntime(handle)->getNeuronInfo(static_cast<uint32_t>(neuronId));
    const jsize n = 2;
    jfloatArray out = env->NewFloatArray(n);
    const jfloat values[n] = {info.activity, info.potential};
    env->SetFloatArrayRegion(out, 0, n, values);
    return out;
}

}  // extern "C"
