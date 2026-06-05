#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "MoonlightAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef AAUDIO_USAGE_GAME
#define AAUDIO_USAGE_GAME 14
#endif

#ifndef AAUDIO_CONTENT_TYPE_SONIFICATION
#define AAUDIO_CONTENT_TYPE_SONIFICATION 4
#endif

typedef aaudio_result_t (*AAudio_createStreamBuilder_fn)(AAudioStreamBuilder** builder);
typedef void (*AAudioStreamBuilder_delete_fn)(AAudioStreamBuilder* builder);
typedef aaudio_result_t (*AAudioStreamBuilder_openStream_fn)(AAudioStreamBuilder* builder, AAudioStream** stream);
typedef void (*AAudioStreamBuilder_setDirection_fn)(AAudioStreamBuilder* builder, aaudio_direction_t direction);
typedef void (*AAudioStreamBuilder_setFormat_fn)(AAudioStreamBuilder* builder, aaudio_format_t format);
typedef void (*AAudioStreamBuilder_setSampleRate_fn)(AAudioStreamBuilder* builder, int32_t sampleRate);
typedef void (*AAudioStreamBuilder_setChannelCount_fn)(AAudioStreamBuilder* builder, int32_t channelCount);
typedef void (*AAudioStreamBuilder_setPerformanceMode_fn)(AAudioStreamBuilder* builder, aaudio_performance_mode_t mode);
typedef void (*AAudioStreamBuilder_setSharingMode_fn)(AAudioStreamBuilder* builder, aaudio_sharing_mode_t mode);
typedef void (*AAudioStreamBuilder_setDataCallback_fn)(AAudioStreamBuilder* builder, AAudioStream_dataCallback callback, void* userData);
typedef void (*AAudioStreamBuilder_setUsage_fn)(AAudioStreamBuilder* builder, aaudio_usage_t usage);
typedef void (*AAudioStreamBuilder_setContentType_fn)(AAudioStreamBuilder* builder, aaudio_content_type_t contentType);
typedef aaudio_result_t (*AAudioStream_requestStart_fn)(AAudioStream* stream);
typedef aaudio_result_t (*AAudioStream_requestStop_fn)(AAudioStream* stream);
typedef aaudio_result_t (*AAudioStream_close_fn)(AAudioStream* stream);
typedef int32_t (*AAudioStream_getFramesPerBurst_fn)(AAudioStream* stream);
typedef int32_t (*AAudioStream_getBufferCapacityInFrames_fn)(AAudioStream* stream);
typedef int32_t (*AAudioStream_getBufferSizeInFrames_fn)(AAudioStream* stream);
typedef int32_t (*AAudioStream_setBufferSizeInFrames_fn)(AAudioStream* stream, int32_t numFrames);
typedef aaudio_performance_mode_t (*AAudioStream_getPerformanceMode_fn)(AAudioStream* stream);
typedef aaudio_sharing_mode_t (*AAudioStream_getSharingMode_fn)(AAudioStream* stream);
typedef const char* (*AAudio_convertResultToText_fn)(aaudio_result_t returnCode);

static void* aaudioLib;
static bool symbolsLoaded;
static AAudio_createStreamBuilder_fn p_AAudio_createStreamBuilder;
static AAudioStreamBuilder_delete_fn p_AAudioStreamBuilder_delete;
static AAudioStreamBuilder_openStream_fn p_AAudioStreamBuilder_openStream;
static AAudioStreamBuilder_setDirection_fn p_AAudioStreamBuilder_setDirection;
static AAudioStreamBuilder_setFormat_fn p_AAudioStreamBuilder_setFormat;
static AAudioStreamBuilder_setSampleRate_fn p_AAudioStreamBuilder_setSampleRate;
static AAudioStreamBuilder_setChannelCount_fn p_AAudioStreamBuilder_setChannelCount;
static AAudioStreamBuilder_setPerformanceMode_fn p_AAudioStreamBuilder_setPerformanceMode;
static AAudioStreamBuilder_setSharingMode_fn p_AAudioStreamBuilder_setSharingMode;
static AAudioStreamBuilder_setDataCallback_fn p_AAudioStreamBuilder_setDataCallback;
static AAudioStreamBuilder_setUsage_fn p_AAudioStreamBuilder_setUsage;
static AAudioStreamBuilder_setContentType_fn p_AAudioStreamBuilder_setContentType;
static AAudioStream_requestStart_fn p_AAudioStream_requestStart;
static AAudioStream_requestStop_fn p_AAudioStream_requestStop;
static AAudioStream_close_fn p_AAudioStream_close;
static AAudioStream_getFramesPerBurst_fn p_AAudioStream_getFramesPerBurst;
static AAudioStream_getBufferCapacityInFrames_fn p_AAudioStream_getBufferCapacityInFrames;
static AAudioStream_getBufferSizeInFrames_fn p_AAudioStream_getBufferSizeInFrames;
static AAudioStream_setBufferSizeInFrames_fn p_AAudioStream_setBufferSizeInFrames;
static AAudioStream_getPerformanceMode_fn p_AAudioStream_getPerformanceMode;
static AAudioStream_getSharingMode_fn p_AAudioStream_getSharingMode;
static AAudio_convertResultToText_fn p_AAudio_convertResultToText;

static pthread_mutex_t streamMutex = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream* stream;
static int channelCount;
static int16_t* ringBuffer;
static int ringCapacitySamples;
static int ringReadIndex;
static int ringWriteIndex;
static int ringUsedSamples;

static const char* resultText(aaudio_result_t result) {
    if (p_AAudio_convertResultToText != NULL) {
        return p_AAudio_convertResultToText(result);
    }
    return "unknown";
}

static void* loadRequiredSymbol(const char* name) {
    void* symbol = dlsym(aaudioLib, name);
    if (symbol == NULL) {
        LOGE("Missing AAudio symbol: %s", name);
    }
    return symbol;
}

static void* loadOptionalSymbol(const char* name) {
    return dlsym(aaudioLib, name);
}

static bool loadAaudioSymbols(void) {
    if (symbolsLoaded) {
        return true;
    }

    aaudioLib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (aaudioLib == NULL) {
        LOGW("libaaudio.so is unavailable: %s", dlerror());
        return false;
    }

#define LOAD_REQUIRED(name) do { \
    p_##name = (name##_fn)loadRequiredSymbol(#name); \
    if (p_##name == NULL) return false; \
} while (0)
#define LOAD_OPTIONAL(name) p_##name = (name##_fn)loadOptionalSymbol(#name)

    LOAD_REQUIRED(AAudio_createStreamBuilder);
    LOAD_REQUIRED(AAudioStreamBuilder_delete);
    LOAD_REQUIRED(AAudioStreamBuilder_openStream);
    LOAD_REQUIRED(AAudioStreamBuilder_setDirection);
    LOAD_REQUIRED(AAudioStreamBuilder_setFormat);
    LOAD_REQUIRED(AAudioStreamBuilder_setSampleRate);
    LOAD_REQUIRED(AAudioStreamBuilder_setChannelCount);
    LOAD_REQUIRED(AAudioStreamBuilder_setPerformanceMode);
    LOAD_REQUIRED(AAudioStreamBuilder_setSharingMode);
    LOAD_REQUIRED(AAudioStreamBuilder_setDataCallback);
    LOAD_REQUIRED(AAudioStream_requestStart);
    LOAD_REQUIRED(AAudioStream_requestStop);
    LOAD_REQUIRED(AAudioStream_close);
    LOAD_REQUIRED(AAudioStream_getFramesPerBurst);
    LOAD_REQUIRED(AAudioStream_getBufferCapacityInFrames);
    LOAD_REQUIRED(AAudioStream_getBufferSizeInFrames);
    LOAD_REQUIRED(AAudioStream_setBufferSizeInFrames);
    LOAD_OPTIONAL(AAudioStreamBuilder_setUsage);
    LOAD_OPTIONAL(AAudioStreamBuilder_setContentType);
    LOAD_OPTIONAL(AAudioStream_getPerformanceMode);
    LOAD_OPTIONAL(AAudioStream_getSharingMode);
    LOAD_OPTIONAL(AAudio_convertResultToText);

#undef LOAD_REQUIRED
#undef LOAD_OPTIONAL

    symbolsLoaded = true;
    return true;
}

static int maxInt(int a, int b) {
    return a > b ? a : b;
}

static int minInt(int a, int b) {
    return a < b ? a : b;
}

static void clearRingLocked(void) {
    ringReadIndex = 0;
    ringWriteIndex = 0;
    ringUsedSamples = 0;
    if (ringBuffer != NULL) {
        memset(ringBuffer, 0, (size_t)ringCapacitySamples * sizeof(*ringBuffer));
    }
}

static void freeRingLocked(void) {
    free(ringBuffer);
    ringBuffer = NULL;
    ringCapacitySamples = 0;
    clearRingLocked();
}

static aaudio_data_callback_result_t dataCallback(AAudioStream* ignoredStream, void* userData, void* audioData, int32_t numFrames) {
    (void)ignoredStream;
    (void)userData;

    int16_t* out = (int16_t*)audioData;
    int requestedSamples = numFrames * channelCount;
    int copiedSamples = 0;

    if (pthread_mutex_trylock(&streamMutex) == 0) {
        int samplesToCopy = minInt(requestedSamples, ringUsedSamples);
        while (samplesToCopy > 0) {
            int chunk = minInt(samplesToCopy, ringCapacitySamples - ringReadIndex);
            memcpy(out + copiedSamples, ringBuffer + ringReadIndex, (size_t)chunk * sizeof(*out));
            ringReadIndex = (ringReadIndex + chunk) % ringCapacitySamples;
            ringUsedSamples -= chunk;
            copiedSamples += chunk;
            samplesToCopy -= chunk;
        }
        pthread_mutex_unlock(&streamMutex);
    }

    if (copiedSamples < requestedSamples) {
        memset(out + copiedSamples, 0, (size_t)(requestedSamples - copiedSamples) * sizeof(*out));
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static aaudio_result_t openAaudioStream(int requestedChannelCount, int sampleRate, aaudio_sharing_mode_t sharingMode) {
    AAudioStreamBuilder* builder = NULL;
    AAudioStream* newStream = NULL;
    aaudio_result_t result = p_AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("AAudio_createStreamBuilder failed: %s", resultText(result));
        return result;
    }

    p_AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    p_AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    p_AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    p_AAudioStreamBuilder_setChannelCount(builder, requestedChannelCount);
    p_AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    p_AAudioStreamBuilder_setSharingMode(builder, sharingMode);
    p_AAudioStreamBuilder_setDataCallback(builder, dataCallback, NULL);

    if (p_AAudioStreamBuilder_setUsage != NULL) {
        p_AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    }
    if (p_AAudioStreamBuilder_setContentType != NULL) {
        p_AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SONIFICATION);
    }

    result = p_AAudioStreamBuilder_openStream(builder, &newStream);
    p_AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGW("AAudio open failed for sharing mode %d: %s", sharingMode, resultText(result));
        return result;
    }

    stream = newStream;
    return AAUDIO_OK;
}

JNIEXPORT jboolean JNICALL Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeIsSupported(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return loadAaudioSymbols() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeSetup(JNIEnv* env, jclass clazz, jint requestedChannelCount, jint sampleRate, jint samplesPerFrame) {
    (void)env;
    (void)clazz;

    if (!loadAaudioSymbols()) {
        return -1;
    }

    pthread_mutex_lock(&streamMutex);
    if (stream != NULL) {
        p_AAudioStream_requestStop(stream);
        p_AAudioStream_close(stream);
        stream = NULL;
    }
    freeRingLocked();
    channelCount = requestedChannelCount;
    pthread_mutex_unlock(&streamMutex);

    aaudio_result_t result = openAaudioStream(requestedChannelCount, sampleRate, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (result != AAUDIO_OK) {
        result = openAaudioStream(requestedChannelCount, sampleRate, AAUDIO_SHARING_MODE_SHARED);
        if (result != AAUDIO_OK) {
            return -2;
        }
    }

    int burstFrames = p_AAudioStream_getFramesPerBurst(stream);
    int targetBufferFrames = maxInt(burstFrames * 2, samplesPerFrame * 2);
    int actualBufferFrames = p_AAudioStream_setBufferSizeInFrames(stream, targetBufferFrames);
    int capacityFrames = p_AAudioStream_getBufferCapacityInFrames(stream);
    int bufferFrames = p_AAudioStream_getBufferSizeInFrames(stream);
    int ringCapacityFrames = maxInt(samplesPerFrame * 6, maxInt(burstFrames * 4, targetBufferFrames * 2));

    pthread_mutex_lock(&streamMutex);
    ringCapacitySamples = ringCapacityFrames * channelCount;
    ringBuffer = (int16_t*)calloc((size_t)ringCapacitySamples, sizeof(*ringBuffer));
    clearRingLocked();
    pthread_mutex_unlock(&streamMutex);

    if (ringBuffer == NULL) {
        LOGE("Failed to allocate AAudio ring buffer");
        pthread_mutex_lock(&streamMutex);
        p_AAudioStream_close(stream);
        stream = NULL;
        pthread_mutex_unlock(&streamMutex);
        return -3;
    }

    int performanceMode = p_AAudioStream_getPerformanceMode != NULL ? p_AAudioStream_getPerformanceMode(stream) : -1;
    int sharingMode = p_AAudioStream_getSharingMode != NULL ? p_AAudioStream_getSharingMode(stream) : -1;
    LOGI("AAudio config: channels=%d sampleRate=%d packetFrames=%d burst=%d targetBuffer=%d actualSet=%d buffer=%d capacity=%d ringFrames=%d perf=%d sharing=%d",
         requestedChannelCount, sampleRate, samplesPerFrame, burstFrames, targetBufferFrames,
         actualBufferFrames, bufferFrames, capacityFrames, ringCapacityFrames, performanceMode, sharingMode);

    return 0;
}

JNIEXPORT void JNICALL Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeStart(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    pthread_mutex_lock(&streamMutex);
    clearRingLocked();
    AAudioStream* localStream = stream;
    pthread_mutex_unlock(&streamMutex);

    if (localStream != NULL) {
        aaudio_result_t result = p_AAudioStream_requestStart(localStream);
        if (result != AAUDIO_OK) {
            LOGE("AAudio requestStart failed: %s", resultText(result));
        }
    }
}

JNIEXPORT void JNICALL Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeStop(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    pthread_mutex_lock(&streamMutex);
    AAudioStream* localStream = stream;
    clearRingLocked();
    pthread_mutex_unlock(&streamMutex);

    if (localStream != NULL) {
        p_AAudioStream_requestStop(localStream);
    }
}

JNIEXPORT void JNICALL Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeWrite(JNIEnv* env, jclass clazz, jshortArray audioData, jint sampleCount) {
    (void)clazz;

    if (audioData == NULL || sampleCount <= 0) {
        return;
    }

    jsize arrayLength = (*env)->GetArrayLength(env, audioData);
    if (sampleCount > arrayLength) {
        sampleCount = arrayLength;
    }

    jboolean isCopy;
    jshort* samples = (*env)->GetShortArrayElements(env, audioData, &isCopy);
    if (samples == NULL) {
        return;
    }

    pthread_mutex_lock(&streamMutex);
    if (ringBuffer != NULL && ringCapacitySamples > 0) {
        int inputOffset = 0;
        int samplesToWrite = sampleCount;

        if (samplesToWrite > ringCapacitySamples) {
            inputOffset = samplesToWrite - ringCapacitySamples;
            samplesToWrite = ringCapacitySamples;
        }

        int freeSamples = ringCapacitySamples - ringUsedSamples;
        if (samplesToWrite > freeSamples) {
            int samplesToDrop = samplesToWrite - freeSamples;
            ringReadIndex = (ringReadIndex + samplesToDrop) % ringCapacitySamples;
            ringUsedSamples -= samplesToDrop;
            LOGW("AAudio ring full; dropped %d samples to avoid latency growth", samplesToDrop);
        }

        while (samplesToWrite > 0) {
            int chunk = minInt(samplesToWrite, ringCapacitySamples - ringWriteIndex);
            memcpy(ringBuffer + ringWriteIndex, samples + inputOffset, (size_t)chunk * sizeof(*ringBuffer));
            ringWriteIndex = (ringWriteIndex + chunk) % ringCapacitySamples;
            ringUsedSamples += chunk;
            inputOffset += chunk;
            samplesToWrite -= chunk;
        }
    }
    pthread_mutex_unlock(&streamMutex);

    (*env)->ReleaseShortArrayElements(env, audioData, samples, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeCleanup(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    pthread_mutex_lock(&streamMutex);
    AAudioStream* localStream = stream;
    stream = NULL;
    freeRingLocked();
    pthread_mutex_unlock(&streamMutex);

    if (localStream != NULL) {
        p_AAudioStream_requestStop(localStream);
        p_AAudioStream_close(localStream);
    }
}
