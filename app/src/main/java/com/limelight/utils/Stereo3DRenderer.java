package com.limelight.utils;

import static com.limelight.utils.ShaderUtils.FRAGMENT_SHADER_SEPARABLE_DILATE;
import static com.limelight.utils.ShaderUtils.VERTEX_SHADER;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Stereo3DRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    // Constants
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private static final float[] TEXTURE_VERTICES = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    private final String AI_MODEL = "midas-midas-v2-w8a8.tflite";
    private final int modelInputHeight = 256;
    private final int modelInputWidth = 256;
    private final int NUM_BUFFERS = 6;
    private final int NUM_INPUT_BUFFERS = 10;
    private final int NUM_SMOOTHED_BUFFERS = 3;
    private final int[] pboHandles = new int[2];

    public static boolean isMovieMode = true;
    private int PBO_SIZE = modelInputWidth * modelInputHeight * 4;

    // Public Static Fields
    public static volatile float fps = 0;
    public static volatile float threeDFps = 0;
    public static volatile float drawDelay = 0.0f;
    public static Boolean isDebugMode = false;
    public static Boolean isActive = false;
    public static String renderer = "CPU";

    // Private Static Fields
    private static float calcFps = 0;
    private static int depthMapResultCount = 0;
    private static float calcThreeDFps = 0;

    // Final Member Variables
    private final Context context;
    private final GLSurfaceView glSurfaceView;
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    private final Object frameLock = new Object();
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private final AtomicBoolean gpuDelegateFailed = new AtomicBoolean(false);
    private final AtomicBoolean isAiResultHandlingRunning = new AtomicBoolean(false);
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);

    // OpenGL Handles
    private int mDilationProgram;
    private int bilateralBlurProgram;
    private int depthMapTextureId;
    private int dibr3dProgram;
    private int simple3dProgram;
    private int videoTextureId;
    private int fboHandle;
    private int fboTextureId;
    private int filterFboHandle;
    private int filteredDepthMapTextureId;
    private int intermediateFboHandle;
    private int intermediateTextureId;
    private int intermediateDilutionFboHandle;
    private int intermediateDilutionTextureId;
    private int gaussIntermediateFboHandle;
    private int gaussIntermediateTextureId;

    // --- VORGELADENE SHADER-LOCATIONS FÜR PERFORMANCE ---
    private int mDilationPosHandle, mDilationTexHandle, mDilationInputTextureHandle,
            mDilationTexelSizeHandle, mDilationRadiusHandle, mDilationDirectionHandle;
    private int mGaussPosHandle, mGaussTexHandle, mGaussInputTextureHandle,
            mGaussTexelSizeHandle, mGaussDirectionHandle, mGaussParallaxHandle;
    private int mDibrPosHandle, mDibrTexHandle, mDibrColorTexHandle, mDibrDepthTexHandle,
            mDibrParallaxHandle, mDibrConvergenceHandle, mDibrShiftHandle, mDibrDebugModeHandle;

    // AI & TFLite Variables
    private GpuDelegate gpuDelegate;
    private Interpreter tflite;
    private NnApiDelegate nnApiDelegate;
    private ByteBuffer tfliteInputBuffer;

    // Other Member Variables
    private long totalDrawTime = 0;
    private long lastFpsTime = 0;
    private ByteBuffer previousFrameForComparison;
    private ByteBuffer currentlyRenderingMap;
    private ExecutorService executorService;
    private BlockingQueue<InferenceResult> filledOutputBuffers;
    private BlockingQueue<ByteBuffer> freeInputBuffers;
    private BlockingQueue<ByteBuffer> freeOutputBuffers;
    private BlockingQueue<ByteBuffer> freeSmoothedBuffers;
    private BlockingQueue<RenderResult> inferenceInputQueue = new ArrayBlockingQueue<>(1);
    private PreferenceConfiguration prefConfig;
    private Surface videoSurface;
    private SurfaceTexture videoSurfaceTexture;
    private final AtomicReference<ByteBuffer> latestDepthMap = new AtomicReference<>(null);
    private float ON_DRAW_CHANGE_TRESHOLD = 2.5f;

    public interface OnSurfaceReadyListener {
        void onStereo3DSurfaceReady(Surface surface);
    }

    static {
        if (!OpenCVLoader.initLocal()) {
            LimeLog.severe("Internal OpenCV library not found. Using OpenCV Manager for initialization");
        } else {
            LimeLog.info("OpenCV library found inside package. Using it!");
        }
    }

    public Stereo3DRenderer(GLSurfaceView view, OnSurfaceReadyListener listener, Context context, PreferenceConfiguration prefConfig) {
        this.glSurfaceView = view;
        this.onSurfaceReadyListener = listener;
        this.context = context;
        this.prefConfig = prefConfig;

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).position(0);
        textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureVertexBuffer.put(TEXTURE_VERTICES).position(0);
    }

    public void setPrefConfig(PreferenceConfiguration prefConfig) {
        this.prefConfig = prefConfig;
    }

    public void onSurfaceDestroyed() {
        LimeLog.info("Quit called. Shutting down 3dRenderer.");
        if (executorService != null) {
            executorService.shutdownNow();
            try {
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    LimeLog.warning("Thread pool did not terminate in time.");
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (nnApiDelegate != null) {
            nnApiDelegate.close();
            nnApiDelegate = null;
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }

        glSurfaceView.queueEvent(() -> {
            GLES20.glDeleteProgram(simple3dProgram);
            GLES20.glDeleteProgram(bilateralBlurProgram);
            GLES20.glDeleteProgram(dibr3dProgram);
            GLES20.glDeleteProgram(mDilationProgram);

            int[] textures = {
                    videoTextureId,
                    depthMapTextureId,
                    filteredDepthMapTextureId,
                    fboTextureId,
                    intermediateTextureId,
                    intermediateDilutionTextureId,

            };
            GLES20.glDeleteTextures(textures.length, textures, 0);

            int[] fbos = {fboHandle, intermediateFboHandle, filterFboHandle, intermediateDilutionFboHandle};
            GLES20.glDeleteFramebuffers(fbos.length, fbos, 0);
        });

        if (filledOutputBuffers != null) filledOutputBuffers.clear();
        currentlyRenderingMap = null;
        prefConfig = null;
        drawDelay = 0.0f;
        calcFps = 0;
        calcThreeDFps = 0.0f;
        renderer = "CPU";
        isActive = false;
    }

    public Surface getVideoSurface() {
        return videoSurface;
    }

    private void initializeGaussIntermediateFbo() {
        gaussIntermediateTextureId = createRgbaTexture(modelInputWidth, modelInputHeight); // Oder passende Textur erstellen
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        gaussIntermediateFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gaussIntermediateFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, gaussIntermediateTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("Gauss Intermediate Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (frameLock) {
            frameAvailable.set(true);
        }
        glSurfaceView.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        videoTextureId = createExternalOESTexture();
        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        videoSurfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(videoSurfaceTexture);

        depthMapTextureId = createEmptyTexture(modelInputWidth, modelInputHeight);

        simple3dProgram = createProgram(VERTEX_SHADER, ShaderUtils.SIMPLE_FRAGMENT_SHADER);
        bilateralBlurProgram = createProgram(VERTEX_SHADER, ShaderUtils.OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER);
        dibr3dProgram = createProgram(VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_3D);
        mDilationProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_SEPARABLE_DILATE);
        if (mDilationProgram == 0) {
            throw new RuntimeException("Konnte Dilation-Shader-Programm nicht erstellen.");
        }

        getShaderLocations();

        initializeFilterFbo();
        initializeIntermediateFbo();
        initializeDilationFbo();
        initializeGaussIntermediateFbo();
        initializeTfLite();
        initializeFbo();
        initBuffer();
        initializePBOs();

        int mapSize = modelInputWidth * modelInputHeight;
        freeSmoothedBuffers = new ArrayBlockingQueue<>(NUM_SMOOTHED_BUFFERS);
        for (int i = 0; i < NUM_SMOOTHED_BUFFERS; i++) {
            freeSmoothedBuffers.offer(ByteBuffer.allocateDirect(mapSize).order(ByteOrder.nativeOrder()));
        }

        int pboSize = modelInputWidth * modelInputHeight * 4;
        previousFrameForComparison = ByteBuffer.allocateDirect(pboSize).order(ByteOrder.nativeOrder());
        int inputPixelSize = modelInputWidth * modelInputHeight * 4;
        freeInputBuffers = new ArrayBlockingQueue<>(NUM_INPUT_BUFFERS);
        inferenceInputQueue = new ArrayBlockingQueue<>(1);
        for (int i = 0; i < NUM_INPUT_BUFFERS; i++) {
            freeInputBuffers.offer(ByteBuffer.allocateDirect(inputPixelSize).order(ByteOrder.nativeOrder()));
        }

        executorService = Executors.newFixedThreadPool(2);

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onStereo3DSurfaceReady(videoSurface);
        }
        if (!isAiResultHandlingRunning.get()) {
            isAiResultHandlingRunning.set(true);
            executorService.submit(new AiResultHandling());
        }
        if (!isAiRunning.get()) {
            isAiRunning.set(true);
            executorService.submit(new AiTask());
        }
        isActive = true;
    }

    private void getShaderLocations() {
        mDilationPosHandle = GLES20.glGetAttribLocation(mDilationProgram, "a_Position");
        mDilationTexHandle = GLES20.glGetAttribLocation(mDilationProgram, "a_TexCoord");
        mDilationInputTextureHandle = GLES20.glGetUniformLocation(mDilationProgram, "s_InputTexture");
        mDilationTexelSizeHandle = GLES20.glGetUniformLocation(mDilationProgram, "u_texelSize");
        mDilationRadiusHandle = GLES20.glGetUniformLocation(mDilationProgram, "u_radius");
        mDilationDirectionHandle = GLES20.glGetUniformLocation(mDilationProgram, "u_direction");

        mGaussPosHandle = GLES20.glGetAttribLocation(bilateralBlurProgram, "a_Position");
        mGaussTexHandle = GLES20.glGetAttribLocation(bilateralBlurProgram, "a_TexCoord");
        mGaussInputTextureHandle = GLES20.glGetUniformLocation(bilateralBlurProgram, "s_InputTexture");
        mGaussTexelSizeHandle = GLES20.glGetUniformLocation(bilateralBlurProgram, "u_texelSize");
        mGaussDirectionHandle = GLES20.glGetUniformLocation(bilateralBlurProgram, "u_blurDirection");
        mGaussParallaxHandle = GLES20.glGetUniformLocation(bilateralBlurProgram, "u_parallax");

        mDibrPosHandle = GLES20.glGetAttribLocation(dibr3dProgram, "a_Position");
        mDibrTexHandle = GLES20.glGetAttribLocation(dibr3dProgram, "a_TexCoord");
        mDibrColorTexHandle = GLES20.glGetUniformLocation(dibr3dProgram, "s_ColorTexture");
        mDibrDepthTexHandle = GLES20.glGetUniformLocation(dibr3dProgram, "s_DepthTexture");
        mDibrParallaxHandle = GLES20.glGetUniformLocation(dibr3dProgram, "u_parallax");
        mDibrConvergenceHandle = GLES20.glGetUniformLocation(dibr3dProgram, "u_convergence");
        mDibrShiftHandle = GLES20.glGetUniformLocation(dibr3dProgram, "u_shift");
        mDibrDebugModeHandle = GLES20.glGetUniformLocation(dibr3dProgram, "u_debugMode");
    }

    private void initializeIntermediateFbo() {
        intermediateTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        intermediateFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, intermediateTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("Intermediate Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void initializeDilationFbo() {
        intermediateDilutionTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        intermediateDilutionFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateDilutionFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, intermediateDilutionTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("Dilation Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private float getParallax() {
        return prefConfig.parallax_depth * 0.25f;
    }

    /**
     * Wendet einen performanten, zweistufigen Dilation-Filter korrekt an.
     * Liest von 'depthMapTextureId', schreibt das Zwischenergebnis nach 'intermediateDilutionFboHandle' (Tex A)
     * und das Endergebnis nach 'intermediateFboHandle' (Tex B).
     */
    private void applyTwoPassDilation() {
        GLES20.glUseProgram(mDilationProgram);
        GLES20.glEnableVertexAttribArray(mDilationPosHandle);
        GLES20.glEnableVertexAttribArray(mDilationTexHandle);
        GLES20.glVertexAttribPointer(mDilationPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(mDilationTexHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glUniform1i(mDilationInputTextureHandle, 0);
        GLES20.glUniform2f(mDilationTexelSizeHandle, 1.0f / modelInputWidth, 1.0f / modelInputHeight);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateDilutionFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
        GLES20.glUniform1i(mDilationRadiusHandle, 5);
        GLES20.glUniform2f(mDilationDirectionHandle, 1.0f, 0.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gaussIntermediateFboHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateDilutionTextureId);
        GLES20.glUniform1i(mDilationRadiusHandle, 5);
        GLES20.glUniform2f(mDilationDirectionHandle, 0.0f, 1.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mDilationPosHandle);
        GLES20.glDisableVertexAttribArray(mDilationTexHandle);
    }

    private void applyTwoPassGaussianBlur() {
        int blurProgram = bilateralBlurProgram;

        GLES20.glUseProgram(blurProgram);

        GLES20.glVertexAttribPointer(mGaussPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(mGaussTexHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(mGaussPosHandle);
        GLES20.glEnableVertexAttribArray(mGaussTexHandle);
        GLES20.glUniform1f(mGaussParallaxHandle, getParallax());

        GLES20.glUniform2f(mGaussTexelSizeHandle, 1.0f / modelInputWidth, 1.0f / modelInputHeight);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        GLES20.glUniform2f(mGaussDirectionHandle, 1.0f, 0.0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gaussIntermediateTextureId);
        GLES20.glUniform1i(mGaussInputTextureHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        GLES20.glUniform2f(mGaussDirectionHandle, 0.0f, 1.0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTextureId);
        GLES20.glUniform1i(mGaussInputTextureHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }


    private void drawBothEyes(int dualBubble3dProgram, float convergence, float shift) {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();
        float parallax = getParallax() * 0.2f;

        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, -parallax, convergence, shift);

        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        drawEye(dualBubble3dProgram, parallax, convergence, shift);
    }

    private void drawEye(int program, float parallax, float convergence, float shift) {
        GLES20.glUseProgram(program);

        GLES20.glVertexAttribPointer(mDibrPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(mDibrTexHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(mDibrPosHandle);
        GLES20.glEnableVertexAttribArray(mDibrTexHandle);

        GLES20.glUniform1i(mDibrDebugModeHandle, isDebugMode ? 1 : 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glUniform1i(mDibrColorTexHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId);
        GLES20.glUniform1i(mDibrDepthTexHandle, 1);
        GLES20.glUniform1f(mDibrParallaxHandle, parallax);
        GLES20.glUniform1f(mDibrConvergenceHandle, convergence);
        GLES20.glUniform1f(mDibrShiftHandle, shift);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawWithShader() {
        if (prefConfig != null) {
            drawBothEyes(dibr3dProgram, prefConfig.convergence_ratio, prefConfig.balance_shift);
        }
    }

    private void initBuffer() {
        if (tflite != null) {
            int inputSize = modelInputHeight * modelInputWidth * 3;
            tfliteInputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());
            int outputSize = modelInputHeight * modelInputWidth;
            freeOutputBuffers = new ArrayBlockingQueue<>(NUM_BUFFERS);
            filledOutputBuffers = new ArrayBlockingQueue<>(NUM_BUFFERS);
            for (int i = 0; i < NUM_BUFFERS; i++) {
                freeOutputBuffers.offer(ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder()));
            }
        }
    }

    private volatile Boolean block = false;

    @Override
    public void onDrawFrame(GL10 gl) {
        long startTime = System.nanoTime();

        synchronized (frameLock) {
            if (!frameAvailable.get()) {
                if (!isMovieMode) {
                    glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                } else {
                    glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                    return;
                }
            } else if (isMovieMode) {
                block = true;
            }
            frameAvailable.set(false);
        }
        try {
            videoSurfaceTexture.updateTexImage();
        } catch (Exception e) {
            Log.w("Stereo3DRenderer", "updateTexImagse failed", e);
            return;
        }

        if (currentlyRenderingMap != null) {
            freeSmoothedBuffers.offer(currentlyRenderingMap);
        }

        if (tflite != null) {
            if (block || !isMovieMode) {
                ByteBuffer pixelBufferForAI = freeInputBuffers.poll();
                if (pixelBufferForAI != null) {
                    boolean success = readPixelsForAI(pixelBufferForAI);
                    if (success) {
                        double difference = hasSceneChangedFast(pixelBufferForAI, previousFrameForComparison);
                        pixelBufferForAI.rewind();
                        previousFrameForComparison.rewind();
                        previousFrameForComparison.put(pixelBufferForAI);

                        if (inferenceInputQueue.offer(new RenderResult(pixelBufferForAI, difference))) {
                            Log.d("AiTask", "Success: The AI will now process this buffer.");
                        } else {
                            freeInputBuffers.offer(pixelBufferForAI);
                        }
                    } else {
                        freeInputBuffers.offer(pixelBufferForAI);
                    }
                }
                ByteBuffer newMap = null;
                if (block && isMovieMode) {
                    while ((newMap = latestDepthMap.getAndSet(null)) == null) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } else {
                    newMap = latestDepthMap.getAndSet(null);
                }
                if (newMap != null) {
                    block = false;
                    currentlyRenderingMap = newMap;
                    depthMapResultCount++;
                }
            }

            if (currentlyRenderingMap != null) {
                uploadLatestDepthMapToGpu(currentlyRenderingMap);
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            applyTwoPassDilation();
            applyTwoPassGaussianBlur();
            drawWithShader();
            long endTime = System.nanoTime();

            if (lastFpsTime == 0) {
                lastFpsTime = startTime;
            }
            totalDrawTime = totalDrawTime + (endTime - lastFpsTime);

            if (endTime - lastFpsTime >= 1_000_000_000) {
                if (fps > 0) {
                    drawDelay = ((float) totalDrawTime / fps / 1000000000f);
                }
                totalDrawTime = 0;
                fps = calcFps;
                calcFps = 0;
                depthMapResultCount = 0;
                threeDFps = calcThreeDFps;
                calcThreeDFps = 0;
                lastFpsTime = endTime;
                int freeInputCap = freeInputBuffers.size() + freeInputBuffers.remainingCapacity();
                int aiInCap = inferenceInputQueue.size() + inferenceInputQueue.remainingCapacity();
                int aiOutCap = filledOutputBuffers.size() + filledOutputBuffers.remainingCapacity();
                int freeSmoothCap = freeSmoothedBuffers.size() + freeSmoothedBuffers.remainingCapacity();

                String queueStatus = String.format(
                        "Queues (Free/Cap) | FreeInput: %d/%d, To_AI: %d/%d, From_AI: %d/%d, Free_Smooth: %d/%d",
                        freeInputBuffers.remainingCapacity(), freeInputCap,
                        inferenceInputQueue.remainingCapacity(), aiInCap,
                        filledOutputBuffers.remainingCapacity(), aiOutCap,
                        freeSmoothedBuffers.remainingCapacity(), freeSmoothCap
                );
                Log.d("Stereo3DRenderer", queueStatus);
            } else {
                calcFps++;
            }
        }
    }

    private ByteBuffer createFlatDepthMap() {
        int mapSize = modelInputWidth * modelInputHeight;
        byte[] flatData = new byte[mapSize];
        Arrays.fill(flatData, (byte) 128);
        ByteBuffer flatMap = ByteBuffer.allocateDirect(mapSize).order(ByteOrder.nativeOrder());
        flatMap.put(flatData);
        flatMap.rewind();
        return flatMap;
    }

    private void uploadLatestDepthMapToGpu(ByteBuffer depthMap) {
        if (depthMap != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, modelInputWidth, modelInputHeight, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, depthMap);
        }
    }

    private void drawQuad(int program, float scale, float offset) {
        GLES20.glUseProgram(program);
        int posHandle = GLES20.glGetAttribLocation(program, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        int offsetHandle = GLES20.glGetUniformLocation(program, "u_xOffset");
        int scaleHandle = GLES20.glGetUniformLocation(program, "u_xScale");
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        if (scaleHandle != -1) GLES20.glUniform1f(scaleHandle, scale);
        if (offsetHandle != -1) GLES20.glUniform1f(offsetHandle, offset);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static class InferenceResult {
        final ByteBuffer pixelBuffer;
        final ByteBuffer rawDepthBuffer;
        InferenceResult(ByteBuffer pixelBuffer, ByteBuffer rawDepthBuffer) {
            this.pixelBuffer = pixelBuffer;
            this.rawDepthBuffer = rawDepthBuffer;
        }
    }

    private static class RenderResult {
        final ByteBuffer pixelBuffer;
        final double imageDifference;
        RenderResult(ByteBuffer pixelBuffer, double imageDifference) {
            this.pixelBuffer = pixelBuffer;
            this.imageDifference = imageDifference;
        }
    }

    public static void convertRgbaToRgb(ByteBuffer rgbaBuffer, ByteBuffer rgbBuffer, int width, int height) {
        Mat rgbaMat = null;
        Mat rgbMat = null;
        try {
            rgbaMat = new Mat(height, width, CvType.CV_8UC4, rgbaBuffer);
            rgbMat = new Mat(height, width, CvType.CV_8UC3, rgbBuffer);
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB);
        } finally {
            if (rgbaMat != null) rgbaMat.release();
            if (rgbMat != null) rgbMat.release();
        }
    }

    private void initializePBOs() {
        PBO_SIZE = modelInputWidth * modelInputHeight * 4;
        GLES30.glGenBuffers(2, pboHandles, 0);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[0]);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboHandles[1]);
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_SIZE, null, GLES30.GL_DYNAMIC_READ);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
    }

    private Boolean readPixelsForAI(ByteBuffer destinationBuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);
        destinationBuffer.rewind();
        GLES20.glReadPixels(0, 0, modelInputWidth, modelInputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, destinationBuffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return true;
    }

    private void initializeTfLite() {
        Interpreter.Options options = new Interpreter.Options();
        try {
            GpuDelegate.Options gpuOptions = new GpuDelegate.Options();
            gpuOptions.setQuantizedModelsAllowed(true);
            gpuOptions.setPrecisionLossAllowed(true);
            gpuOptions.setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
            gpuDelegate = new GpuDelegate(gpuOptions);
            options.addDelegate(gpuDelegate);
            LimeLog.info("GPU Delegate aktiviert");
            renderer = "GPU";
            tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
        } catch (Exception e) {
            LimeLog.info("GPU Delegate nicht verfügbar: " + e.getMessage());
            if (gpuDelegate != null) gpuDelegate.close();
            try {
                nnApiDelegate = new NnApiDelegate();
                options.addDelegate(nnApiDelegate);
                tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
                LimeLog.info("NNAPI Delegate aktiviert");
                renderer = "NNAPI";
            } catch (Exception exception) {
                LimeLog.info("NNAPI Delegate nicht verfügbar: " + e.getMessage());
                if (nnApiDelegate != null) nnApiDelegate.close();
                try {
                    LimeLog.info("Fallback: CPU");
                    tflite = new Interpreter(loadModelFile(context, AI_MODEL), new Interpreter.Options());
                    renderer = "CPU";
                } catch (Exception ex) {
                    reinitializeTfLiteOnCpu();
                }
            }
        }
    }

    private void reinitializeTfLiteOnCpu() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setUseNNAPI(true);
            options.setNumThreads(4);
            tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
            LimeLog.info("Successfully re-initialized TFLite interpreter on CPU.");
        } catch (IOException e) {
            LimeLog.severe("Failed to re-initialize TFLite model on CPU: " + e.getMessage());
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    private void initializeFbo() {
        fboTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        fboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.severe("Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void initializeFilterFbo() {
        filteredDepthMapTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        filterFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.severe("Filter Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private int createExternalOESTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }

    private int createEmptyTexture(int width, int height) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }

    private int createRgbaTexture(int width, int height) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }

    private int createProgram(String vertex, String fragment) {
        int vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShader, vertex);
        GLES20.glCompileShader(vertexShader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("Could not compile vertex shader:");
            LimeLog.severe(GLES20.glGetShaderInfoLog(vertexShader));
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }

        int fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShader, fragment);
        GLES20.glCompileShader(fragmentShader);

        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("Could not compile fragment shader:");
            LimeLog.severe(GLES20.glGetShaderInfoLog(fragmentShader));
            GLES20.glDeleteShader(fragmentShader);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                LimeLog.severe("Could not link program: ");
                LimeLog.severe(GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    private double computeColorSimilarity(ByteBuffer newPixelBuffer, ByteBuffer oldPixelBuffer) {
        if (newPixelBuffer == null || oldPixelBuffer == null || newPixelBuffer.capacity() != oldPixelBuffer.capacity()) {
            return 0.0;
        }
        newPixelBuffer.rewind();
        oldPixelBuffer.rewind();
        Mat mat1 = null, mat2 = null, matBGR1 = null, matBGR2 = null, histB1 = null, histG1 = null, histR1 = null, histB2 = null, histG2 = null, histR2 = null;
        List<Mat> bgrPlanes1 = new ArrayList<>(), bgrPlanes2 = new ArrayList<>();
        try {
            mat1 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, newPixelBuffer);
            mat2 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, oldPixelBuffer);
            matBGR1 = new Mat();
            matBGR2 = new Mat();
            Imgproc.cvtColor(mat1, matBGR1, Imgproc.COLOR_RGBA2BGR);
            Imgproc.cvtColor(mat2, matBGR2, Imgproc.COLOR_RGBA2BGR);
            Core.split(matBGR1, bgrPlanes1);
            Core.split(matBGR2, bgrPlanes2);
            histB1 = new Mat(); histG1 = new Mat(); histR1 = new Mat();
            histB2 = new Mat(); histG2 = new Mat(); histR2 = new Mat();
            int histSize = 16;
            MatOfFloat histRange = new MatOfFloat(0f, 256f);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes1.get(0)), new MatOfInt(0), new Mat(), histB1, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes1.get(1)), new MatOfInt(0), new Mat(), histG1, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes1.get(2)), new MatOfInt(0), new Mat(), histR1, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes2.get(0)), new MatOfInt(0), new Mat(), histB2, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes2.get(1)), new MatOfInt(0), new Mat(), histG2, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes2.get(2)), new MatOfInt(0), new Mat(), histR2, new MatOfInt(histSize), histRange);
            Core.normalize(histB1, histB1, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histG1, histG1, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histR1, histR1, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histB2, histB2, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histG2, histG2, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histR2, histR2, 0, 1, Core.NORM_MINMAX);
            double corrB = Imgproc.compareHist(histB1, histB2, Imgproc.HISTCMP_CORREL);
            double corrG = Imgproc.compareHist(histG1, histG2, Imgproc.HISTCMP_CORREL);
            double corrR = Imgproc.compareHist(histR1, histR2, Imgproc.HISTCMP_CORREL);
            return 1f - Math.max(0, (corrB + corrG + corrR) / 3.0);
        } finally {
            if (mat1 != null) mat1.release();
            if (mat2 != null) mat2.release();
            if (matBGR1 != null) matBGR1.release();
            if (matBGR2 != null) matBGR2.release();
            if (histB1 != null) histB1.release();
            if (histG1 != null) histG1.release();
            if (histR1 != null) histR1.release();
            if (histB2 != null) histB2.release();
            if (histG2 != null) histG2.release();
            if (histR2 != null) histR2.release();
            for (Mat m : bgrPlanes1) if (m != null) m.release();
            for (Mat m : bgrPlanes2) if (m != null) m.release();
        }
    }

    private double hasFrameChangedSignificantlyOCV(ByteBuffer newPixelBuffer, ByteBuffer oldPixelBuffer) {
        if (newPixelBuffer == null || oldPixelBuffer == null || newPixelBuffer.capacity() != oldPixelBuffer.capacity()) {
            return 1.0;
        }
        Mat mat1 = null, mat2 = null, gray1 = null, gray2 = null, edges1 = null, edges2 = null,
                histGray1 = null, histGray2 = null, histEdge1 = null, histEdge2 = null;
        try {
            mat1 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, newPixelBuffer);
            mat2 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, oldPixelBuffer);
            gray1 = new Mat();
            gray2 = new Mat();
            Imgproc.cvtColor(mat1, gray1, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(mat2, gray2, Imgproc.COLOR_RGBA2GRAY);
            edges1 = new Mat();
            edges2 = new Mat();
            Mat gradX1 = new Mat(), gradY1 = new Mat(), gradX2 = new Mat(), gradY2 = new Mat();
            Imgproc.Sobel(gray1, gradX1, CvType.CV_16S, 1, 0);
            Imgproc.Sobel(gray1, gradY1, CvType.CV_16S, 0, 1);
            Core.convertScaleAbs(gradX1, gradX1);
            Core.convertScaleAbs(gradY1, gradY1);
            Core.addWeighted(gradX1, 0.5, gradY1, 0.5, 0, edges1);
            Imgproc.Sobel(gray2, gradX2, CvType.CV_16S, 1, 0);
            Imgproc.Sobel(gray2, gradY2, CvType.CV_16S, 0, 1);
            Core.convertScaleAbs(gradX2, gradX2);
            Core.convertScaleAbs(gradY2, gradY2);
            Core.addWeighted(gradX2, 0.5, gradY2, 0.5, 0, edges2);
            gradX1.release(); gradY1.release(); gradX2.release(); gradY2.release();
            histGray1 = new Mat(); histGray2 = new Mat();
            Imgproc.calcHist(Collections.singletonList(gray1), new MatOfInt(0), new Mat(), histGray1, new MatOfInt(256), new MatOfFloat(0f, 256f));
            Imgproc.calcHist(Collections.singletonList(gray2), new MatOfInt(0), new Mat(), histGray2, new MatOfInt(256), new MatOfFloat(0f, 256f));
            histEdge1 = new Mat(); histEdge2 = new Mat();
            Imgproc.calcHist(Collections.singletonList(edges1), new MatOfInt(0), new Mat(), histEdge1, new MatOfInt(256), new MatOfFloat(0f, 256f));
            Imgproc.calcHist(Collections.singletonList(edges2), new MatOfInt(0), new Mat(), histEdge2, new MatOfInt(256), new MatOfFloat(0f, 256f));
            double grayDiff = 1.0 - Imgproc.compareHist(histGray1, histGray2, Imgproc.HISTCMP_CORREL);
            double edgeDiff = 1.0 - Imgproc.compareHist(histEdge1, histEdge2, Imgproc.HISTCMP_CORREL);
            return 0.5 * grayDiff + 0.5 * edgeDiff;
        } finally {
            if (mat1 != null) mat1.release(); if (mat2 != null) mat2.release();
            if (gray1 != null) gray1.release(); if (gray2 != null) gray2.release();
            if (edges1 != null) edges1.release(); if (edges2 != null) edges2.release();
            if (histGray1 != null) histGray1.release(); if (histGray2 != null) histGray2.release();
            if (histEdge1 != null) histEdge1.release(); if (histEdge2 != null) histEdge2.release();
        }
    }

    private double hasSceneChangedFast(ByteBuffer currentFrame, ByteBuffer previousFrame) {
        if (currentFrame == null || previousFrame == null || currentFrame.capacity() != previousFrame.capacity()) {
            return 0.0;
        }
        currentFrame.rewind();
        previousFrame.rewind();
        long totalDifference = 0;
        int pixelsSampled = 0;
        final int PIXEL_STRIDE = 4;
        final int PIXEL_SAMPLE_RATE = 32;
        final int ROW_SAMPLE_RATE = 32;
        final int SAMPLE_STRIDE = PIXEL_STRIDE * PIXEL_SAMPLE_RATE;
        final int ROW_STRIDE = modelInputWidth * PIXEL_STRIDE * ROW_SAMPLE_RATE;
        for (int row = 0; row < currentFrame.capacity(); row += ROW_STRIDE) {
            for (int col = 0; col < modelInputWidth * PIXEL_STRIDE; col += SAMPLE_STRIDE) {
                int index = row + col;
                if (index + 2 >= currentFrame.capacity()) break;
                totalDifference += Math.abs((currentFrame.get(index) & 0xFF) - (previousFrame.get(index) & 0xFF));
                totalDifference += Math.abs((currentFrame.get(index + 1) & 0xFF) - (previousFrame.get(index + 1) & 0xFF));
                totalDifference += Math.abs((currentFrame.get(index + 2) & 0xFF) - (previousFrame.get(index + 2) & 0xFF));
                pixelsSampled++;
            }
        }
        if (pixelsSampled == 0) return 0.0;
        return (double) totalDifference / pixelsSampled;
    }

    private class AiTask implements Runnable {
        private ByteBuffer previousRawMap = null;
        @Override
        public void run() {
            ByteBuffer pixelBuffer = null;
            double difference = 0.0f;
            while (!Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                long waitTime, aiTime, aiTime_end;
                try {
                    if (tflite == null) return;
                    RenderResult result = inferenceInputQueue.take();
                    pixelBuffer = result.pixelBuffer;
                    difference = result.imageDifference;
                    ByteBuffer outputBuffer = freeOutputBuffers.take();
                    waitTime = System.nanoTime();
                    outputBuffer.rewind();
                    if (difference > ON_DRAW_CHANGE_TRESHOLD || previousRawMap == null) {
                        tfliteInputBuffer.rewind();
                        pixelBuffer.rewind();
                        convertRgbaToRgb(pixelBuffer, tfliteInputBuffer, modelInputWidth, modelInputHeight);
                        aiTime = System.nanoTime();
                        ReflectivePaddingInt8Minimal.applyReflectedPadding(tfliteInputBuffer);
                        tflite.run(tfliteInputBuffer, outputBuffer);
                        if (previousRawMap == null) {
                            previousRawMap = ByteBuffer.allocateDirect(outputBuffer.capacity());
                        }
                        previousRawMap.clear();
                        outputBuffer.rewind();
                        previousRawMap.put(outputBuffer);
                        previousRawMap.rewind();
                    } else {
                        outputBuffer.clear();
                        previousRawMap.rewind();
                        outputBuffer.put(previousRawMap);
                        outputBuffer.rewind();
                    }
                    calcThreeDFps++;
                    aiTime_end = System.nanoTime();
                    filledOutputBuffers.put(new InferenceResult(pixelBuffer, outputBuffer));
                    pixelBuffer = null;
                } catch (InterruptedException e) {
                    LimeLog.severe("AI inference failed: " + e.getMessage());
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LimeLog.severe("AI inference failed: " + e.getMessage());
                    gpuDelegateFailed.set(true);
                } finally {
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    if (pixelBuffer != null) {
                        freeInputBuffers.offer(pixelBuffer);
                    }
                    Log.d("Stereo3DRenderer", "CalculateTime AiDepthMap: " + duration + " ms");
                }
            }
            isAiRunning.set(false);
        }
    }

    private class AiResultHandling implements Runnable {
        private static final double DEPTH_DIFF_THRESHOLD = 0.1;
        private static final double MAX_SMOOTHING = 1.0;
        private static final double MIN_SMOOTHING = 0.0;
        private Mat previousSmoothedMat; // Bleibt Member, hält Zustand über Frames
        private boolean isFirstFrame = true;

        // --- Wiederverwendbare Mat-Objekte für diesen Thread ---
        private Mat reusableRawMat = null; // Wird nur als Header verwendet
        private Mat reusableRawFloat = new Mat();
        private Mat reusableDiffMat = new Mat();
        private Mat reusableDiffFloat = new Mat();
        private Mat reusableMeanMat = new Mat();
        private Mat reusableVarianceMat = new Mat();
        private Mat reusableNormalizedForShader = new Mat();
        private Mat reusableOutputMat = new Mat();

        @Override
        public void run() {
            ByteBuffer resultBuffer = createFlatDepthMap(); // Initialer flacher Puffer
            InferenceResult result = null;

            // Markierung, ob der Thread noch laufen soll
            while (isAiResultHandlingRunning.get() && !Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                try {
                    result = filledOutputBuffers.take(); // Blockiert, bis Ergebnis da ist
                    resultBuffer = freeSmoothedBuffers.take(); // Blockiert, bis Puffer frei ist

                    // Verarbeite nur das letzte verfügbare Ergebnis
                    InferenceResult intermediate;
                    while ((intermediate = filledOutputBuffers.poll()) != null) {
                        freeInputBuffers.offer(result.pixelBuffer); // Gib alte Buffer zurück
                        freeOutputBuffers.offer(result.rawDepthBuffer);
                        result = intermediate; // Behalte das Neueste
                    }

                    // --- Verwende wiederverwendbare Mats ---
                    // Erstelle nur den Header neu, zeigt auf den Buffer, keine Datenkopie
                    reusableRawMat = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC1, result.rawDepthBuffer);
                    reusableRawMat.convertTo(reusableRawFloat, CvType.CV_32F);

                    if (isFirstFrame) {
                        // Initialisiere previousSmoothedMat sicher
                        if (previousSmoothedMat == null) {
                            previousSmoothedMat = new Mat();
                        }
                        reusableRawFloat.copyTo(previousSmoothedMat);
                        isFirstFrame = false;
                    } else if (previousSmoothedMat == null || previousSmoothedMat.empty()) {
                        // Fallback, falls Initialisierung fehlschlug
                        if (previousSmoothedMat == null) previousSmoothedMat = new Mat();
                        reusableRawFloat.copyTo(previousSmoothedMat);
                        LimeLog.warning("previousSmoothedMat war null/leer, neu initialisiert in Schleife.");
                    }

                    // --- Differenzberechnung ---
                    Core.absdiff(reusableRawFloat, previousSmoothedMat, reusableDiffMat);
                    Scalar sumDiff = Core.sumElems(reusableDiffMat);
                    // Vermeide Division durch Null, falls Mat leer ist
                    double totalPixels = reusableDiffMat.total();
                    double meanDiff = (totalPixels > 0) ? sumDiff.val[0] / (totalPixels * 255.0) : 0.0;

                    reusableDiffMat.convertTo(reusableDiffFloat, CvType.CV_32F, 1.0 / 255.0);
                    Scalar meanVal = Core.mean(reusableDiffFloat);

                    // Stelle sicher, dass meanMat die korrekte Größe/Typ hat, bevor setTo verwendet wird
                    if (reusableMeanMat.empty() || !reusableMeanMat.size().equals(reusableDiffFloat.size()) || reusableMeanMat.type() != reusableDiffFloat.type()) {
                        reusableMeanMat.create(reusableDiffFloat.size(), reusableDiffFloat.type());
                    }
                    reusableMeanMat.setTo(new Scalar(meanVal.val[0]));

                    Core.subtract(reusableDiffFloat, reusableMeanMat, reusableVarianceMat); // reusableVarianceMat wird überschrieben
                    Core.multiply(reusableVarianceMat, reusableVarianceMat, reusableVarianceMat); // In-place quadrieren
                    double varianceSum = Core.sumElems(reusableVarianceMat).val[0];
                    double stdDev = (totalPixels > 0) ? Math.sqrt(varianceSum / totalPixels) : 0.0;

                    double depthMapDifference = Math.max(meanDiff, stdDev);

                    // --- Glättungsfaktor ---
                    double smoothing;
                    if (depthMapDifference > DEPTH_DIFF_THRESHOLD) {
                        smoothing = MAX_SMOOTHING;
                    } else if (depthMapDifference > 0.01) {
                        smoothing = depthMapDifference * 2.0; // Evtl. Faktor anpassen
                        smoothing = Math.max(MIN_SMOOTHING, Math.min(MAX_SMOOTHING, smoothing));
                    } else {
                        smoothing = 0;
                    }

                    // --- Glättung anwenden ---
                    Imgproc.accumulateWeighted(reusableRawFloat, previousSmoothedMat, smoothing);

                    // --- Normalisieren für Ausgabe ---
                    Core.MinMaxLocResult mmr = Core.minMaxLoc(previousSmoothedMat);
                    double range = mmr.maxVal - mmr.minVal;
                    if (range < 1e-6) range = 1e-6; // Schutz vor Division durch Null

                    Core.subtract(previousSmoothedMat, new Scalar(mmr.minVal), reusableNormalizedForShader);
                    Core.divide(reusableNormalizedForShader, new Scalar(range), reusableNormalizedForShader);

                    reusableNormalizedForShader.convertTo(reusableOutputMat, CvType.CV_8U, 255.0);

                    // --- Ergebnis in ByteBuffer kopieren ---
                    if (reusableOutputMat.isContinuous() && resultBuffer.hasArray()) {
                        reusableOutputMat.get(0, 0, resultBuffer.array());
                        // Wichtig: Limit muss evtl. angepasst werden, wenn Puffer größer ist
                        resultBuffer.limit(reusableOutputMat.rows() * reusableOutputMat.cols() * (int)reusableOutputMat.elemSize());
                    } else {
                        // Langsamerer Fallback, falls nicht kontinuierlich oder kein Array hat
                        int bufferSize = modelInputWidth * modelInputHeight;
                        byte[] data = new byte[bufferSize];
                        reusableOutputMat.get(0, 0, data);
                        resultBuffer.put(data);
                    }
                    resultBuffer.rewind(); // Puffer für den Konsumenten vorbereiten

                    latestDepthMap.set(resultBuffer);
                    resultBuffer = null; // Besitz wurde an latestDepthMap übergeben

                } catch (InterruptedException e) {
                    LimeLog.warning("AiResultHandling interrupted.");
                    Thread.currentThread().interrupt(); // Interrupt-Status wiederherstellen
                    break; // Schleife verlassen
                } catch (Exception e) {
                    LimeLog.severe("AI result handling exception: " + e.getMessage());
                    // Hier könnte man überlegen, ob isFirstFrame zurückgesetzt werden soll
                } finally {
                    // Gib nur die Buffer zurück, deren Besitz nicht übertragen wurde
                    if (resultBuffer != null) freeSmoothedBuffers.offer(resultBuffer);
                    if (result != null) {
                        freeInputBuffers.offer(result.pixelBuffer);
                        freeOutputBuffers.offer(result.rawDepthBuffer);
                        result = null; // Referenz löschen
                    }
                    // Die wiederverwendeten Mat-Objekte werden NICHT hier freigegeben

                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    Log.d("Stereo3DRenderer", "CalculateTime AiResult: " + duration + " ms");
                }
            } // Ende while-Schleife

            // --- Aufräumen, wenn der Thread endet ---
            releaseMat(previousSmoothedMat); previousSmoothedMat = null;
            // releaseMat(reusableRawMat); // Ist nur ein Header, muss nicht freigegeben werden
            releaseMat(reusableRawFloat);
            releaseMat(reusableDiffMat);
            releaseMat(reusableDiffFloat);
            releaseMat(reusableMeanMat);
            releaseMat(reusableVarianceMat);
            releaseMat(reusableNormalizedForShader);
            releaseMat(reusableOutputMat);

            isAiResultHandlingRunning.set(false); // Signal setzen, dass der Thread beendet ist
            LimeLog.info("AiResultHandling finished.");
        } // Ende run()

        // Hilfsmethode zum sicheren Freigeben von Mats
        private void releaseMat(Mat mat) {
            if (mat != null && !mat.empty()) {
                mat.release();
            }
        }
    }
}