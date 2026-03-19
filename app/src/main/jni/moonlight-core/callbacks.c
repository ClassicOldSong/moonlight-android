#include <jni.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <Limelight.h>

#include <opus.h>
#include <opus_multistream.h>
#include <android/log.h>

#include <cpu-features.h>

static OpusMSDecoder* Decoder;
static OPUS_MULTISTREAM_CONFIGURATION OpusConfig;

static JavaVM *JVM;
static pthread_key_t JniEnvKey;
static pthread_once_t JniEnvKeyInitOnce = PTHREAD_ONCE_INIT;
static jclass GlobalBridgeClass;
static jmethodID BridgeDrSetupMethod;
static jmethodID BridgeDrStartMethod;
static jmethodID BridgeDrStopMethod;
static jmethodID BridgeDrCleanupMethod;
static jmethodID BridgeDrSubmitDecodeUnitMethod;
static jmethodID BridgeArInitMethod;
static jmethodID BridgeArStartMethod;
static jmethodID BridgeArStopMethod;
static jmethodID BridgeArCleanupMethod;
static jmethodID BridgeArPlaySampleMethod;
static jmethodID BridgeClStageStartingMethod;
static jmethodID BridgeClStageCompleteMethod;
static jmethodID BridgeClStageFailedMethod;
static jmethodID BridgeClConnectionStartedMethod;
static jmethodID BridgeClConnectionTerminatedMethod;
static jmethodID BridgeClRumbleMethod;
static jmethodID BridgeClConnectionStatusUpdateMethod;
static jmethodID BridgeClSetHdrModeMethod;
static jmethodID BridgeClRumbleTriggersMethod;
static jmethodID BridgeClSetMotionEventStateMethod;
static jmethodID BridgeClSetControllerLEDMethod;
static jbyteArray DecodedFrameBuffer;
static jshortArray DecodedAudioBuffer;

#define MIC_SAMPLE_RATE 48000
#define MIC_CHANNEL_COUNT 1
#define MIC_FRAME_SIZE 960
#define MIC_DEFAULT_BITRATE 24000
#define MIC_MAX_ENCODED_PACKET 1024
#define MIC_MAX_BUFFERED_SAMPLES (MIC_FRAME_SIZE * 12)

static OpusEncoder* MicEncoder;
static pthread_t MicEncoderThread;
static pthread_mutex_t MicEncoderMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t MicEncoderCond = PTHREAD_COND_INITIALIZER;
static opus_int16* MicSampleBuffer;
static size_t MicSampleBufferCount;
static size_t MicSampleBufferCapacity;
static bool MicEncoderThreadRunning;
static bool MicEncoderStopRequested;
static bool MicStreamingActive;
static bool MicFirstPacketLogged;

static void MicLog(const char* format, ...) {
    va_list va;
    va_start(va, format);
    __android_log_vprint(ANDROID_LOG_INFO, "moonlight-mic", format, va);
    va_end(va);
}

static void clearMicBufferedSamplesLocked(void) {
    MicSampleBufferCount = 0;
}

static int ensureMicSampleCapacityLocked(size_t minimumCapacity) {
    opus_int16* resizedBuffer;
    size_t newCapacity = MicSampleBufferCapacity == 0 ? MIC_FRAME_SIZE * 4 : MicSampleBufferCapacity;

    while (newCapacity < minimumCapacity) {
        newCapacity *= 2;
    }

    resizedBuffer = realloc(MicSampleBuffer, newCapacity * sizeof(opus_int16));
    if (resizedBuffer == NULL) {
        return -1;
    }

    MicSampleBuffer = resizedBuffer;
    MicSampleBufferCapacity = newCapacity;
    return 0;
}

static int compareTimespec(const struct timespec* lhs, const struct timespec* rhs) {
    if (lhs->tv_sec == rhs->tv_sec) {
        if (lhs->tv_nsec == rhs->tv_nsec) {
            return 0;
        }
        return lhs->tv_nsec < rhs->tv_nsec ? -1 : 1;
    }

    return lhs->tv_sec < rhs->tv_sec ? -1 : 1;
}

static int64_t diffTimespecNs(const struct timespec* lhs, const struct timespec* rhs) {
    return ((int64_t)lhs->tv_sec - rhs->tv_sec) * 1000000000LL +
            ((int64_t)lhs->tv_nsec - rhs->tv_nsec);
}

static void addTimespecNs(struct timespec* value, int64_t deltaNs) {
    value->tv_sec += (time_t)(deltaNs / 1000000000LL);
    value->tv_nsec += (long)(deltaNs % 1000000000LL);

    if (value->tv_nsec >= 1000000000L) {
        value->tv_sec++;
        value->tv_nsec -= 1000000000L;
    }
}

static void* MicEncoderWorker(void* context) {
    opus_int16 frame[MIC_FRAME_SIZE];
    unsigned char encodedPacket[MIC_MAX_ENCODED_PACKET];
    const int64_t frameDurationNs = (1000000000LL * MIC_FRAME_SIZE) / MIC_SAMPLE_RATE;
    struct timespec nextSendDeadline = {0};
    bool pacingActive = false;

    while (true) {
        struct timespec now;
        int encodedBytes;
        int sendResult;

        pthread_mutex_lock(&MicEncoderMutex);
        while (!MicEncoderStopRequested &&
                (!MicStreamingActive || MicSampleBufferCount < MIC_FRAME_SIZE)) {
            pthread_cond_wait(&MicEncoderCond, &MicEncoderMutex);
        }

        if (MicEncoderStopRequested) {
            pthread_mutex_unlock(&MicEncoderMutex);
            break;
        }

        if (MicEncoder == NULL) {
            pthread_mutex_unlock(&MicEncoderMutex);
            pacingActive = false;
            continue;
        }

        memcpy(frame, MicSampleBuffer, sizeof(frame));
        MicSampleBufferCount -= MIC_FRAME_SIZE;
        if (MicSampleBufferCount > 0) {
            memmove(MicSampleBuffer,
                    MicSampleBuffer + MIC_FRAME_SIZE,
                    MicSampleBufferCount * sizeof(opus_int16));
        }
        pthread_mutex_unlock(&MicEncoderMutex);

        clock_gettime(CLOCK_MONOTONIC, &now);
        if (!pacingActive) {
            nextSendDeadline = now;
            pacingActive = true;
        }
        else if (diffTimespecNs(&now, &nextSendDeadline) > (frameDurationNs * 2)) {
            nextSendDeadline = now;
        }

        if (compareTimespec(&nextSendDeadline, &now) > 0) {
            clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &nextSendDeadline, NULL);
        }
        addTimespecNs(&nextSendDeadline, frameDurationNs);

        encodedBytes = opus_encode(MicEncoder,
                                   frame,
                                   MIC_FRAME_SIZE,
                                   encodedPacket,
                                   (opus_int32)sizeof(encodedPacket));
        if (encodedBytes <= 0) {
            continue;
        }

        sendResult = LiSendMicrophoneOpusDataEx(encodedPacket, encodedBytes, MIC_FRAME_SIZE);
        if (sendResult >= 0 && !MicFirstPacketLogged) {
            MicFirstPacketLogged = true;
            MicLog("Sent first client microphone packet (%d bytes Opus)", encodedBytes);
        }
        else if (sendResult < 0) {
            MicLog("LiSendMicrophoneOpusDataEx() failed for microphone capture");
        }
    }

    return NULL;
}

static void TeardownMicrophoneEncoder(void) {
    bool shouldJoin;
    pthread_t threadToJoin;

    pthread_mutex_lock(&MicEncoderMutex);
    MicEncoderStopRequested = true;
    MicStreamingActive = false;
    pthread_cond_broadcast(&MicEncoderCond);
    shouldJoin = MicEncoderThreadRunning;
    threadToJoin = MicEncoderThread;
    pthread_mutex_unlock(&MicEncoderMutex);

    if (shouldJoin) {
        pthread_join(threadToJoin, NULL);
    }

    pthread_mutex_lock(&MicEncoderMutex);
    MicEncoderThreadRunning = false;
    MicEncoderStopRequested = false;
    clearMicBufferedSamplesLocked();
    if (MicEncoder != NULL) {
        opus_encoder_destroy(MicEncoder);
        MicEncoder = NULL;
    }
    free(MicSampleBuffer);
    MicSampleBuffer = NULL;
    MicSampleBufferCapacity = 0;
    MicFirstPacketLogged = false;
    pthread_mutex_unlock(&MicEncoderMutex);
}

void DetachThread(void* context) {
    (*JVM)->DetachCurrentThread(JVM);
}

void JniEnvKeyInit(void) {
    // Create a TLS slot for the JNIEnv. We aren't in
    // a pthread during init, so we must wait until we
    // are to initialize this.
    pthread_key_create(&JniEnvKey, DetachThread);
}

JNIEnv* GetThreadEnv(void) {
    JNIEnv* env;

    // First check if this is already attached to the JVM
    if ((*JVM)->GetEnv(JVM, (void**)&env, JNI_VERSION_1_4) == JNI_OK) {
        return env;
    }

    // Create the TLS slot now that we're safely in a pthread
    pthread_once(&JniEnvKeyInitOnce, JniEnvKeyInit);

    // Try the TLS to see if we already have a JNIEnv
    env = pthread_getspecific(JniEnvKey);
    if (env)
        return env;

    // This is the thread's first JNI call, so attach now
    (*JVM)->AttachCurrentThread(JVM, &env, NULL);

    // Write our JNIEnv to TLS, so we detach before dying
    pthread_setspecific(JniEnvKey, env);

    return env;
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_init(JNIEnv *env, jclass clazz) {
    (*env)->GetJavaVM(env, &JVM);
    GlobalBridgeClass = (*env)->NewGlobalRef(env, (*env)->FindClass(env, "com/limelight/nvstream/jni/MoonBridge"));
    BridgeDrSetupMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDrSetup", "(IIII)I");
    BridgeDrStartMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDrStart", "()V");
    BridgeDrStopMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDrStop", "()V");
    BridgeDrCleanupMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDrCleanup", "()V");
    BridgeDrSubmitDecodeUnitMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDrSubmitDecodeUnit", "([BIIIICJJ)I");
    BridgeArInitMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeArInit", "(III)I");
    BridgeArStartMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeArStart", "()V");
    BridgeArStopMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeArStop", "()V");
    BridgeArCleanupMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeArCleanup", "()V");
    BridgeArPlaySampleMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeArPlaySample", "([S)V");
    BridgeClStageStartingMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClStageStarting", "(I)V");
    BridgeClStageCompleteMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClStageComplete", "(I)V");
    BridgeClStageFailedMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClStageFailed", "(II)V");
    BridgeClConnectionStartedMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClConnectionStarted", "()V");
    BridgeClConnectionTerminatedMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClConnectionTerminated", "(I)V");
    BridgeClRumbleMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClRumble", "(SSS)V");
    BridgeClConnectionStatusUpdateMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClConnectionStatusUpdate", "(I)V");
    BridgeClSetHdrModeMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClSetHdrMode", "(Z[B)V");
    BridgeClRumbleTriggersMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClRumbleTriggers", "(SSS)V");
    BridgeClSetMotionEventStateMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClSetMotionEventState", "(SBS)V");
    BridgeClSetControllerLEDMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeClSetControllerLED", "(SBBB)V");
}

int BridgeDrSetup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) {
    JNIEnv* env = GetThreadEnv();
    int err;

    err = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeDrSetupMethod, videoFormat, width, height, redrawRate);
    if ((*env)->ExceptionCheck(env)) {
        // This is called on a Java thread, so it's safe to return
        return -1;
    }
    else if (err != 0) {
        return err;
    }

    // Use a 32K frame buffer that will increase if needed
    DecodedFrameBuffer = (*env)->NewGlobalRef(env, (*env)->NewByteArray(env, 32768));

    return 0;
}

void BridgeDrStart(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDrStartMethod);
}

void BridgeDrStop(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDrStopMethod);
}

void BridgeDrCleanup(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->DeleteGlobalRef(env, DecodedFrameBuffer);

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDrCleanupMethod);
}

int BridgeDrSubmitDecodeUnit(PDECODE_UNIT decodeUnit) {
    JNIEnv* env = GetThreadEnv();
    int ret;

    // Increase the size of our frame data buffer if our frame won't fit
    if ((*env)->GetArrayLength(env, DecodedFrameBuffer) < decodeUnit->fullLength) {
        (*env)->DeleteGlobalRef(env, DecodedFrameBuffer);
        DecodedFrameBuffer = (*env)->NewGlobalRef(env, (*env)->NewByteArray(env, decodeUnit->fullLength));
    }

    PLENTRY currentEntry;
    int offset;

    currentEntry = decodeUnit->bufferList;
    offset = 0;
    while (currentEntry != NULL) {
        // Submit parameter set NALUs separately from picture data
        if (currentEntry->bufferType != BUFFER_TYPE_PICDATA) {
            // Use the beginning of the buffer each time since this is a separate
            // invocation of the decoder each time.
            (*env)->SetByteArrayRegion(env, DecodedFrameBuffer, 0, currentEntry->length, (jbyte*)currentEntry->data);

            ret = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeDrSubmitDecodeUnitMethod,
                                              DecodedFrameBuffer, currentEntry->length, currentEntry->bufferType,
                                              decodeUnit->frameNumber, decodeUnit->frameType, (jchar)decodeUnit->frameHostProcessingLatency,
                                              (jlong)decodeUnit->receiveTimeMs, (jlong)decodeUnit->enqueueTimeMs);
            if ((*env)->ExceptionCheck(env)) {
                // We will crash here
                (*JVM)->DetachCurrentThread(JVM);
                return DR_OK;
            }
            else if (ret != DR_OK) {
                return ret;
            }
        }
        else {
            (*env)->SetByteArrayRegion(env, DecodedFrameBuffer, offset, currentEntry->length, (jbyte*)currentEntry->data);
            offset += currentEntry->length;
        }

        currentEntry = currentEntry->next;
    }

    ret = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeDrSubmitDecodeUnitMethod,
                                       DecodedFrameBuffer, offset, BUFFER_TYPE_PICDATA,
                                       decodeUnit->frameNumber, decodeUnit->frameType, (jchar)decodeUnit->frameHostProcessingLatency,
                                       (jlong)decodeUnit->receiveTimeMs, (jlong)decodeUnit->enqueueTimeMs);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
        return DR_OK;
    }
    else {
        return ret;
    }
}

int BridgeArInit(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int flags) {
    JNIEnv* env = GetThreadEnv();
    int err;

    err = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeArInitMethod, audioConfiguration, opusConfig->sampleRate, opusConfig->samplesPerFrame);
    if ((*env)->ExceptionCheck(env)) {
        // This is called on a Java thread, so it's safe to return
        err = -1;
    }
    if (err == 0) {
        memcpy(&OpusConfig, opusConfig, sizeof(*opusConfig));
        Decoder = opus_multistream_decoder_create(opusConfig->sampleRate,
                                                  opusConfig->channelCount,
                                                  opusConfig->streams,
                                                  opusConfig->coupledStreams,
                                                  opusConfig->mapping,
                                                  &err);
        if (Decoder == NULL) {
            (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeArCleanupMethod);
            return -1;
        }

        // We know ahead of time what the buffer size will be for decoded audio, so pre-allocate it
        DecodedAudioBuffer = (*env)->NewGlobalRef(env, (*env)->NewShortArray(env, opusConfig->channelCount * opusConfig->samplesPerFrame));
    }

    return err;
}

void BridgeArStart(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeArStartMethod);
}

void BridgeArStop(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeArStopMethod);
}

void BridgeArCleanup() {
    JNIEnv* env = GetThreadEnv();

    opus_multistream_decoder_destroy(Decoder);

    (*env)->DeleteGlobalRef(env, DecodedAudioBuffer);

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeArCleanupMethod);
}

void BridgeArDecodeAndPlaySample(char* sampleData, int sampleLength) {
    JNIEnv* env = GetThreadEnv();

    jshort* decodedData = (*env)->GetPrimitiveArrayCritical(env, DecodedAudioBuffer, NULL);

    int decodeLen = opus_multistream_decode(Decoder,
                                            (const unsigned char*)sampleData,
                                            sampleLength,
                                            decodedData,
                                            OpusConfig.samplesPerFrame,
                                            0);
    if (decodeLen > 0) {
        // We must release the array elements before making further JNI calls
        (*env)->ReleasePrimitiveArrayCritical(env, DecodedAudioBuffer, decodedData, 0);

        (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeArPlaySampleMethod, DecodedAudioBuffer);
        if ((*env)->ExceptionCheck(env)) {
            // We will crash here
            (*JVM)->DetachCurrentThread(JVM);
        }
    }
    else {
        // We can abort here to avoid the copy back since no data was modified
        (*env)->ReleasePrimitiveArrayCritical(env, DecodedAudioBuffer, decodedData, JNI_ABORT);
    }
}

void BridgeClStageStarting(int stage) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClStageStartingMethod, stage);
}

void BridgeClStageComplete(int stage) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClStageCompleteMethod, stage);
}

void BridgeClStageFailed(int stage, int errorCode) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClStageFailedMethod, stage, errorCode);
}

void BridgeClConnectionStarted(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClConnectionStartedMethod);
}

void BridgeClConnectionTerminated(int errorCode) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClConnectionTerminatedMethod, errorCode);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
    }
}

void BridgeClRumble(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) {
    JNIEnv* env = GetThreadEnv();

    // The seemingly redundant short casts are required in order to convert the unsigned short to a signed short.
    // If we leave it as an unsigned short, CheckJNI will fail when the value exceeds 32767. The cast itself is
    // fine because the Java code treats the value as unsigned even though it's stored in a signed type.
    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClRumbleMethod, controllerNumber, (short)lowFreqMotor, (short)highFreqMotor);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
    }
}

void BridgeClConnectionStatusUpdate(int connectionStatus) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClConnectionStatusUpdateMethod, connectionStatus);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
        return;
    }
}

void BridgeClSetHdrMode(bool enabled) {
    JNIEnv* env = GetThreadEnv();

    jbyteArray hdrMetadataByteArray = NULL;
    SS_HDR_METADATA hdrMetadata;

    // Check if HDR metadata was provided
    if (enabled && LiGetHdrMetadata(&hdrMetadata)) {
        hdrMetadataByteArray = (*env)->NewByteArray(env, sizeof(SS_HDR_METADATA));
        (*env)->SetByteArrayRegion(env, hdrMetadataByteArray, 0, sizeof(SS_HDR_METADATA), (jbyte*)&hdrMetadata);
    }

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClSetHdrModeMethod, enabled, hdrMetadataByteArray);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
    }
}

void BridgeClRumbleTriggers(unsigned short controllerNumber, unsigned short leftTrigger, unsigned short rightTrigger) {
    JNIEnv* env = GetThreadEnv();

    // The seemingly redundant short casts are required in order to convert the unsigned short to a signed short.
    // If we leave it as an unsigned short, CheckJNI will fail when the value exceeds 32767. The cast itself is
    // fine because the Java code treats the value as unsigned even though it's stored in a signed type.
    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClRumbleTriggersMethod, controllerNumber, (short)leftTrigger, (short)rightTrigger);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
    }
}

void BridgeClSetMotionEventState(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClSetMotionEventStateMethod, controllerNumber, motionType, reportRateHz);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
    }
}

void BridgeClSetControllerLED(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) {
    JNIEnv* env = GetThreadEnv();

    // These jbyte casts are necessary to satisfy CheckJNI
    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeClSetControllerLEDMethod, controllerNumber, (jbyte)r, (jbyte)g, (jbyte)b);
    if ((*env)->ExceptionCheck(env)) {
        // We will crash here
        (*JVM)->DetachCurrentThread(JVM);
    }
}

void BridgeClLogMessage(const char* format, ...) {
    va_list va;
    va_start(va, format);
    __android_log_vprint(ANDROID_LOG_INFO, "moonlight-common-c", format, va);
    va_end(va);
}

static DECODER_RENDERER_CALLBACKS BridgeVideoRendererCallbacks = {
        .setup = BridgeDrSetup,
        .start = BridgeDrStart,
        .stop = BridgeDrStop,
        .cleanup = BridgeDrCleanup,
        .submitDecodeUnit = BridgeDrSubmitDecodeUnit,
};

static AUDIO_RENDERER_CALLBACKS BridgeAudioRendererCallbacks = {
        .init = BridgeArInit,
        .start = BridgeArStart,
        .stop = BridgeArStop,
        .cleanup = BridgeArCleanup,
        .decodeAndPlaySample = BridgeArDecodeAndPlaySample,
        .capabilities = CAPABILITY_SUPPORTS_ARBITRARY_AUDIO_DURATION
};

static CONNECTION_LISTENER_CALLBACKS BridgeConnListenerCallbacks = {
        .stageStarting = BridgeClStageStarting,
        .stageComplete = BridgeClStageComplete,
        .stageFailed = BridgeClStageFailed,
        .connectionStarted = BridgeClConnectionStarted,
        .connectionTerminated = BridgeClConnectionTerminated,
        .logMessage = BridgeClLogMessage,
        .rumble = BridgeClRumble,
        .connectionStatusUpdate = BridgeClConnectionStatusUpdate,
        .setHdrMode = BridgeClSetHdrMode,
        .rumbleTriggers = BridgeClRumbleTriggers,
        .setMotionEventState = BridgeClSetMotionEventState,
        .setControllerLED = BridgeClSetControllerLED,
};

static bool
hasFastAes() {
    if (android_getCpuCount() <= 2) {
        return false;
    }

    switch (android_getCpuFamily()) {
        case ANDROID_CPU_FAMILY_ARM:
            return !!(android_getCpuFeatures() & ANDROID_CPU_ARM_FEATURE_AES);
        case ANDROID_CPU_FAMILY_ARM64:
            return !!(android_getCpuFeatures() & ANDROID_CPU_ARM64_FEATURE_AES);
        case ANDROID_CPU_FAMILY_X86:
        case ANDROID_CPU_FAMILY_X86_64:
            return !!(android_getCpuFeatures() & ANDROID_CPU_X86_FEATURE_AES_NI);
        case ANDROID_CPU_FAMILY_MIPS:
        case ANDROID_CPU_FAMILY_MIPS64:
            return false;
        default:
            // Assume new architectures will all have crypto acceleration (RISC-V will)
            return true;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_isMicrophoneStreamActive(JNIEnv *env, jclass clazz) {
    return LiIsMicrophoneStreamActive();
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_isMicrophoneEncryptionEnabled(JNIEnv *env, jclass clazz) {
    return LiIsMicrophoneEncryptionEnabled();
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_setupMicrophoneEncoder(JNIEnv *env, jclass clazz,
                                                                  jint sampleRate, jint channelCount,
                                                                  jint bitrate) {
    int err = OPUS_OK;

    if (sampleRate != MIC_SAMPLE_RATE || channelCount != MIC_CHANNEL_COUNT) {
        MicLog("Unsupported microphone encoder format: %d Hz, %d channels", sampleRate, channelCount);
        return -1;
    }

    pthread_mutex_lock(&MicEncoderMutex);
    if (MicEncoder != NULL) {
        pthread_mutex_unlock(&MicEncoderMutex);
        return 0;
    }

    MicEncoder = opus_encoder_create(sampleRate, channelCount, OPUS_APPLICATION_VOIP, &err);
    if (MicEncoder == NULL || err != OPUS_OK) {
        MicEncoder = NULL;
        pthread_mutex_unlock(&MicEncoderMutex);
        MicLog("opus_encoder_create() failed for microphone capture: %s", opus_strerror(err));
        return -1;
    }

    opus_encoder_ctl(MicEncoder, OPUS_SET_BITRATE(bitrate > 0 ? bitrate : MIC_DEFAULT_BITRATE));
    opus_encoder_ctl(MicEncoder, OPUS_SET_VBR(1));
    opus_encoder_ctl(MicEncoder, OPUS_SET_COMPLEXITY(10));
    opus_encoder_ctl(MicEncoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(MicEncoder, OPUS_SET_LSB_DEPTH(16));
    opus_encoder_ctl(MicEncoder, OPUS_SET_DTX(0));
    opus_encoder_ctl(MicEncoder, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(MicEncoder, OPUS_SET_PACKET_LOSS_PERC(5));
    opus_encoder_ctl(MicEncoder, OPUS_SET_EXPERT_FRAME_DURATION(OPUS_FRAMESIZE_20_MS));

    clearMicBufferedSamplesLocked();
    MicStreamingActive = false;
    MicEncoderStopRequested = false;
    MicFirstPacketLogged = false;

    if (!MicEncoderThreadRunning) {
        if (pthread_create(&MicEncoderThread, NULL, MicEncoderWorker, NULL) != 0) {
            opus_encoder_destroy(MicEncoder);
            MicEncoder = NULL;
            pthread_mutex_unlock(&MicEncoderMutex);
            MicLog("Failed to create microphone encoder worker thread");
            return -1;
        }

        MicEncoderThreadRunning = true;
    }

    pthread_mutex_unlock(&MicEncoderMutex);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_startMicrophoneStreaming(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&MicEncoderMutex);
    clearMicBufferedSamplesLocked();
    MicFirstPacketLogged = false;
    MicStreamingActive = MicEncoder != NULL;
    pthread_cond_broadcast(&MicEncoderCond);
    pthread_mutex_unlock(&MicEncoderMutex);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_stopMicrophoneStreaming(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&MicEncoderMutex);
    MicStreamingActive = false;
    clearMicBufferedSamplesLocked();
    pthread_cond_broadcast(&MicEncoderCond);
    pthread_mutex_unlock(&MicEncoderMutex);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_cleanupMicrophoneEncoder(JNIEnv *env, jclass clazz) {
    TeardownMicrophoneEncoder();
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_queueMicrophonePcm(JNIEnv *env, jclass clazz,
                                                              jshortArray pcmData, jint sampleCount) {
    jsize arrayLength;
    jshort* samples;
    size_t requestedSamples;

    if (pcmData == NULL || sampleCount <= 0) {
        return 0;
    }

    arrayLength = (*env)->GetArrayLength(env, pcmData);
    if (arrayLength <= 0) {
        return 0;
    }

    if (sampleCount > arrayLength) {
        sampleCount = arrayLength;
    }

    samples = (*env)->GetShortArrayElements(env, pcmData, NULL);
    if (samples == NULL) {
        return -1;
    }

    requestedSamples = (size_t)sampleCount;

    pthread_mutex_lock(&MicEncoderMutex);
    if (MicEncoder == NULL) {
        pthread_mutex_unlock(&MicEncoderMutex);
        (*env)->ReleaseShortArrayElements(env, pcmData, samples, JNI_ABORT);
        return -1;
    }

    if (ensureMicSampleCapacityLocked(MicSampleBufferCount + requestedSamples) != 0) {
        pthread_mutex_unlock(&MicEncoderMutex);
        (*env)->ReleaseShortArrayElements(env, pcmData, samples, JNI_ABORT);
        return -1;
    }

    memcpy(MicSampleBuffer + MicSampleBufferCount, samples, requestedSamples * sizeof(opus_int16));
    MicSampleBufferCount += requestedSamples;
    if (MicSampleBufferCount > MIC_MAX_BUFFERED_SAMPLES) {
        const size_t trimSamples = MicSampleBufferCount - MIC_MAX_BUFFERED_SAMPLES;
        memmove(MicSampleBuffer,
                MicSampleBuffer + trimSamples,
                (MicSampleBufferCount - trimSamples) * sizeof(opus_int16));
        MicSampleBufferCount = MIC_MAX_BUFFERED_SAMPLES;
    }
    pthread_cond_signal(&MicEncoderCond);
    pthread_mutex_unlock(&MicEncoderMutex);

    (*env)->ReleaseShortArrayElements(env, pcmData, samples, JNI_ABORT);
    return sampleCount;
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_startConnection(JNIEnv *env, jclass clazz,
                                                           jstring address, jstring appVersion, jstring gfeVersion,
                                                           jstring rtspSessionUrl, jint serverCodecModeSupport,
                                                           jint width, jint height, jint fps,
                                                           jint bitrate, jint packetSize, jint streamingRemotely,
                                                           jint audioConfiguration, jint supportedVideoFormats,
                                                           jint clientRefreshRateX100,
                                                           jbyteArray riAesKey, jbyteArray riAesIv,
                                                           jint videoCapabilities,
                                                           jint colorSpace, jint colorRange,
                                                           jboolean enableMicrophone) {
    SERVER_INFORMATION serverInfo = {
            .address = (*env)->GetStringUTFChars(env, address, 0),
            .serverInfoAppVersion = (*env)->GetStringUTFChars(env, appVersion, 0),
            .serverInfoGfeVersion = gfeVersion ? (*env)->GetStringUTFChars(env, gfeVersion, 0) : NULL,
            .rtspSessionUrl = rtspSessionUrl ? (*env)->GetStringUTFChars(env, rtspSessionUrl, 0) : NULL,
            .serverCodecModeSupport = serverCodecModeSupport,
    };
    STREAM_CONFIGURATION streamConfig = {
            .width = width,
            .height = height,
            .fps = fps,
            .bitrate = bitrate,
            .packetSize = packetSize,
            .streamingRemotely = streamingRemotely,
            .audioConfiguration = audioConfiguration,
            .supportedVideoFormats = supportedVideoFormats,
            .clientRefreshRateX100 = clientRefreshRateX100,
            .enableMic = enableMicrophone,
            .encryptionFlags = ENCFLG_AUDIO | (enableMicrophone ? ENCFLG_MICROPHONE : 0),
            .colorSpace = colorSpace,
            .colorRange = colorRange
    };

    jbyte* riAesKeyBuf = (*env)->GetByteArrayElements(env, riAesKey, NULL);
    memcpy(streamConfig.remoteInputAesKey, riAesKeyBuf, sizeof(streamConfig.remoteInputAesKey));
    (*env)->ReleaseByteArrayElements(env, riAesKey, riAesKeyBuf, JNI_ABORT);

    jbyte* riAesIvBuf = (*env)->GetByteArrayElements(env, riAesIv, NULL);
    memcpy(streamConfig.remoteInputAesIv, riAesIvBuf, sizeof(streamConfig.remoteInputAesIv));
    (*env)->ReleaseByteArrayElements(env, riAesIv, riAesIvBuf, JNI_ABORT);

    BridgeVideoRendererCallbacks.capabilities = videoCapabilities;

    // Enable all encryption features if the platform has fast AES support
    if (hasFastAes()) {
        streamConfig.encryptionFlags = ENCFLG_ALL;
    }

    int ret = LiStartConnection(&serverInfo,
                                &streamConfig,
                                &BridgeConnListenerCallbacks,
                                &BridgeVideoRendererCallbacks,
                                &BridgeAudioRendererCallbacks,
                                NULL, 0,
                                NULL, 0);

    (*env)->ReleaseStringUTFChars(env, address, serverInfo.address);
    (*env)->ReleaseStringUTFChars(env, appVersion, serverInfo.serverInfoAppVersion);
    if (gfeVersion != NULL) {
        (*env)->ReleaseStringUTFChars(env, gfeVersion, serverInfo.serverInfoGfeVersion);
    }
    if (rtspSessionUrl != NULL) {
        (*env)->ReleaseStringUTFChars(env, rtspSessionUrl, serverInfo.rtspSessionUrl);
    }

    return ret;
}
