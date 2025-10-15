package com.limelight.utils;

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
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
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
    private int pboIndex = 0;

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
    private int bilateralBlurProgram;
    private int depthMapTextureId;
    private int dibr3dProgram;

    private final AtomicReference<ByteBuffer> latestDepthMap = new AtomicReference<>(null);
    private int fboHandle;
    private int fboTextureId;
    private int filterFboHandle;
    private int filteredDepthMapTextureId;
    private int intermediateFboHandle;
    private int intermediateTextureId;
    private int simple3dProgram;
    private int videoTextureId;

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

            int[] textures = {
                    videoTextureId,
                    depthMapTextureId,
                    filteredDepthMapTextureId,
                    fboTextureId,
                    intermediateTextureId
            };
            GLES20.glDeleteTextures(textures.length, textures, 0);

            int[] fbos = {fboHandle, intermediateFboHandle, filterFboHandle};
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

        simple3dProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.SIMPLE_FRAGMENT_SHADER);
        bilateralBlurProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.OPTIMIZED_SINGLE_PASS_GAUSSIAN_BLUR_SHADER);
        dibr3dProgram = createProgram(ShaderUtils.VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_3D);

        initializeFilterFbo();
        initializeIntermediateFbo();
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

    private float getParallax() {
        return prefConfig.parallax_depth * 0.2f;
    }

    private void applyTwoPassGaussianBlur() {
        int blurProgram = bilateralBlurProgram;

        GLES20.glUseProgram(blurProgram);

        int posHandle = GLES20.glGetAttribLocation(blurProgram, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(blurProgram, "a_TexCoord");
        int inputTextureHandle = GLES20.glGetUniformLocation(blurProgram, "s_InputTexture");
        int texelSizeHandle = GLES20.glGetUniformLocation(blurProgram, "u_texelSize");
        int directionHandle = GLES20.glGetUniformLocation(blurProgram, "u_blurDirection");
        int parallaxHandle = GLES20.glGetUniformLocation(blurProgram, "u_parallax");
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glUniform1f(parallaxHandle, getParallax());

        GLES20.glUniform2f(texelSizeHandle, 1.0f / modelInputWidth, 1.0f / modelInputHeight);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        GLES20.glUniform2f(directionHandle, 1.0f, 0.0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthMapTextureId);
        GLES20.glUniform1i(inputTextureHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);

        GLES20.glUniform2f(directionHandle, 0.0f, 1.0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTextureId);
        GLES20.glUniform1i(inputTextureHandle, 0);

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
        int posHandle = GLES20.glGetAttribLocation(program, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        int colorTexHandle = GLES20.glGetUniformLocation(program, "s_ColorTexture");
        int depthTexHandle = GLES20.glGetUniformLocation(program, "s_DepthTexture");
        int parallaxHandle = GLES20.glGetUniformLocation(program, "u_parallax");
        int convergenceHandle = GLES20.glGetUniformLocation(program, "u_convergence");
        int shiftHandle = GLES20.glGetUniformLocation(program, "u_shift");
        int debugModeHandle = GLES20.glGetUniformLocation(program, "u_debugMode");

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        GLES20.glUniform1i(debugModeHandle, isDebugMode ? 1 : 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glUniform1i(colorTexHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId);
        GLES20.glUniform1i(depthTexHandle, 1);
        GLES20.glUniform1f(parallaxHandle, parallax);
        GLES20.glUniform1f(convergenceHandle, convergence);
        GLES20.glUniform1f(shiftHandle, shift);
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

        long startTimeAi = System.nanoTime();
        long endTimeAi = System.nanoTime();
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
                        }
                    }
                } else {
                    newMap = latestDepthMap.getAndSet(null);
                }
                if (newMap != null) {
                    block = false;
                    currentlyRenderingMap = newMap;
                    depthMapResultCount++;
                    endTimeAi = System.nanoTime();
                    Log.d("Stereo3DRenderer", "DepthMap OutputSpeed " + (endTimeAi - startTimeAi) / 1_000_000 + " ms");
                }
            }

            if (currentlyRenderingMap != null) {
                uploadLatestDepthMapToGpu(currentlyRenderingMap);
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
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
            if (rgbaMat != null) {
                rgbaMat.release();
            }
            if (rgbMat != null) {
                rgbMat.release();
            }
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
            gpuDelegate.close();
            try {
                nnApiDelegate = new NnApiDelegate();
                options.addDelegate(nnApiDelegate);
                tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
                LimeLog.info("NNAPI Delegate aktiviert");
                renderer = "NNAPI";
            } catch (Exception exception) {
                LimeLog.info("NNAPI Delegate nicht verfügbar: " + e.getMessage());
                nnApiDelegate.close();
                try {
                    LimeLog.info("Fallback: CPU");
                    tflite = new Interpreter(loadModelFile(context, AI_MODEL), options);
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

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("Could not compile shader " + type + ":");
            LimeLog.severe(GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private int createProgram(String vertex, String fragment) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        if (vertexShader == 0) return 0;
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (fragmentShader == 0) return 0;

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
            return 0.0; // komplett unterschiedlich
        }

        // ByteBuffers zurücksetzen
        newPixelBuffer.rewind();
        oldPixelBuffer.rewind();

        Mat mat1 = null, mat2 = null;
        Mat matBGR1 = null, matBGR2 = null;
        Mat histB1 = null, histG1 = null, histR1 = null;
        Mat histB2 = null, histG2 = null, histR2 = null;

        List<Mat> bgrPlanes1 = null;
        List<Mat> bgrPlanes2 = null;
        try {
            // Mats aus ByteBuffer
            mat1 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, newPixelBuffer);
            mat2 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, oldPixelBuffer);

            // RGBA -> BGR konvertieren
            matBGR1 = new Mat();
            matBGR2 = new Mat();
            Imgproc.cvtColor(mat1, matBGR1, Imgproc.COLOR_RGBA2BGR);
            Imgproc.cvtColor(mat2, matBGR2, Imgproc.COLOR_RGBA2BGR);

            // Core.split vorbereiten
            bgrPlanes1 = new ArrayList<>();
            bgrPlanes1.add(new Mat());
            bgrPlanes1.add(new Mat());
            bgrPlanes1.add(new Mat());
            Core.split(matBGR1, bgrPlanes1);

            bgrPlanes2 = new ArrayList<>();
            bgrPlanes2.add(new Mat());
            bgrPlanes2.add(new Mat());
            bgrPlanes2.add(new Mat());
            Core.split(matBGR2, bgrPlanes2);

            // Histogramme
            histB1 = new Mat();
            histG1 = new Mat();
            histR1 = new Mat();
            histB2 = new Mat();
            histG2 = new Mat();
            histR2 = new Mat();

            int histSize = 16; // grobe Farbanalyse
            float[] range = {0f, 256f};
            MatOfFloat histRange = new MatOfFloat(range);

            // Histogramme berechnen
            Imgproc.calcHist(Collections.singletonList(bgrPlanes1.get(0)), new MatOfInt(0), new Mat(), histB1, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes1.get(1)), new MatOfInt(0), new Mat(), histG1, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes1.get(2)), new MatOfInt(0), new Mat(), histR1, new MatOfInt(histSize), histRange);

            Imgproc.calcHist(Collections.singletonList(bgrPlanes2.get(0)), new MatOfInt(0), new Mat(), histB2, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes2.get(1)), new MatOfInt(0), new Mat(), histG2, new MatOfInt(histSize), histRange);
            Imgproc.calcHist(Collections.singletonList(bgrPlanes2.get(2)), new MatOfInt(0), new Mat(), histR2, new MatOfInt(histSize), histRange);

            // Normalisieren
            Core.normalize(histB1, histB1, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histG1, histG1, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histR1, histR1, 0, 1, Core.NORM_MINMAX);

            Core.normalize(histB2, histB2, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histG2, histG2, 0, 1, Core.NORM_MINMAX);
            Core.normalize(histR2, histR2, 0, 1, Core.NORM_MINMAX);

            // Histogramm-Korrelation pro Kanal
            double corrB = Imgproc.compareHist(histB1, histB2, Imgproc.HISTCMP_CORREL);
            double corrG = Imgproc.compareHist(histG1, histG2, Imgproc.HISTCMP_CORREL);
            double corrR = Imgproc.compareHist(histR1, histR2, Imgproc.HISTCMP_CORREL);

            return 1f - Math.max(0, (corrB + corrG + corrR) / 3.0);

        } finally {
            // Alle Mats freigeben
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
            for (Mat m : new Mat[]{bgrPlanes1.get(0), bgrPlanes1.get(1), bgrPlanes1.get(2)})
                m.release();
            for (Mat m : new Mat[]{bgrPlanes2.get(0), bgrPlanes2.get(1), bgrPlanes2.get(2)})
                m.release();
        }
    }



    private double hasFrameChangedSignificantlyOCV(ByteBuffer newPixelBuffer, ByteBuffer oldPixelBuffer) {
        if (newPixelBuffer == null || oldPixelBuffer == null || newPixelBuffer.capacity() != oldPixelBuffer.capacity()) {
            return 1.0; // maximal unterschiedliche Frames
        }

        Mat mat1 = null, mat2 = null;
        Mat gray1 = null, gray2 = null;
        Mat edges1 = null, edges2 = null;
        Mat histGray1 = null, histGray2 = null;
        Mat histEdge1 = null, histEdge2 = null;

        try {
            mat1 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, newPixelBuffer);
            mat2 = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC4, oldPixelBuffer);

            // Graustufen
            gray1 = new Mat();
            gray2 = new Mat();
            Imgproc.cvtColor(mat1, gray1, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(mat2, gray2, Imgproc.COLOR_RGBA2GRAY);

            // Kanten (Sobel)
            edges1 = new Mat();
            edges2 = new Mat();
            Mat gradX1 = new Mat(), gradY1 = new Mat();
            Mat gradX2 = new Mat(), gradY2 = new Mat();
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

            gradX1.release();
            gradY1.release();
            gradX2.release();
            gradY2.release();

            // Histogramme Graustufen
            histGray1 = new Mat();
            histGray2 = new Mat();
            Imgproc.calcHist(Collections.singletonList(gray1), new MatOfInt(0), new Mat(), histGray1, new MatOfInt(256), new MatOfFloat(0f, 256f));
            Imgproc.calcHist(Collections.singletonList(gray2), new MatOfInt(0), new Mat(), histGray2, new MatOfInt(256), new MatOfFloat(0f, 256f));

            // Histogramme Kanten
            histEdge1 = new Mat();
            histEdge2 = new Mat();
            Imgproc.calcHist(Collections.singletonList(edges1), new MatOfInt(0), new Mat(), histEdge1, new MatOfInt(256), new MatOfFloat(0f, 256f));
            Imgproc.calcHist(Collections.singletonList(edges2), new MatOfInt(0), new Mat(), histEdge2, new MatOfInt(256), new MatOfFloat(0f, 256f));

            // Vergleich: Graustufen + Kanten
            double grayDiff = 1.0 - Imgproc.compareHist(histGray1, histGray2, Imgproc.HISTCMP_CORREL);
            double edgeDiff = 1.0 - Imgproc.compareHist(histEdge1, histEdge2, Imgproc.HISTCMP_CORREL);

            // Kombiniere beide Differenzen (Gewichtung kann angepasst werden)
            double combinedDiff = 0.5 * grayDiff + 0.5 * edgeDiff;
            return combinedDiff;

        } finally {
            if (mat1 != null) mat1.release();
            if (mat2 != null) mat2.release();
            if (gray1 != null) gray1.release();
            if (gray2 != null) gray2.release();
            if (edges1 != null) edges1.release();
            if (edges2 != null) edges2.release();
            if (histGray1 != null) histGray1.release();
            if (histGray2 != null) histGray2.release();
            if (histEdge1 != null) histEdge1.release();
            if (histEdge2 != null) histEdge2.release();
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

        double averageDifference = (double) totalDifference / pixelsSampled;

        return averageDifference;
    }

    private class AiTask implements Runnable {

        private ByteBuffer previousRawMap = null;

        @Override
        public void run() {
            ByteBuffer pixelBuffer = null;
            double difference = 0.0f;
            while (!Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                long waitTime = System.nanoTime();
                long aiTime = System.nanoTime();
                long aiTime_end = System.nanoTime();
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
                    long waitTimeText = (waitTime - startTime) / 1_000_000;
                    long aitimeText = (aiTime_end - aiTime) / 1_000_000;
                    if (pixelBuffer != null) {
                        freeInputBuffers.offer(pixelBuffer);
                    }
                    Log.d("Stereo3DRenderer", "CalculateTime AiDepthMap: " + duration + " ms " + filledOutputBuffers.remainingCapacity() + " " + waitTimeText + " ms " + "aitime: " + aitimeText);
                }
            }
            isAiRunning.set(false);
        }
    }

    private class AiResultHandling implements Runnable {

        private static final double DEPTH_DIFF_THRESHOLD = 0.1; // large jump threshold
        private static final double DEPTH_DIFF_AVERAGE_THRESHOLD = 0.05; // large jump threshold
        private static final double MAX_SMOOTHING = 1.0; // full adoption
        private static final double MIN_SMOOTHING = 0.0; // ignore

        private Mat previousSmoothedMat;
        private boolean isFirstFrame = true;

        @Override
        public void run() {
            ByteBuffer resultBuffer = createFlatDepthMap();
            InferenceResult result = null;

            while (!Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                Mat rawMat = null;
                Mat rawFloat = null;
                try {
                    result = filledOutputBuffers.take();
                    resultBuffer = freeSmoothedBuffers.take();

                    // Take latest intermediate frame if multiple available
                    InferenceResult intermediate;
                    while ((intermediate = filledOutputBuffers.poll()) != null) {
                        freeInputBuffers.offer(result.pixelBuffer);
                        freeOutputBuffers.offer(result.rawDepthBuffer);
                        result = intermediate;
                    }

                    rawMat = new Mat(modelInputHeight, modelInputWidth, CvType.CV_8UC1, result.rawDepthBuffer);
                    rawFloat = new Mat();
                    rawMat.convertTo(rawFloat, CvType.CV_32F); // keep un-normalized

                    if (isFirstFrame) {
                        previousSmoothedMat = rawFloat.clone();
                        isFirstFrame = false;
                    }

                    // --- Calculate robust depth difference ---
                    Mat diffMat = new Mat();
                    Core.absdiff(rawFloat, previousSmoothedMat, diffMat);

                    // Mean difference (global)
                    Scalar sumDiff = Core.sumElems(diffMat);
                    double meanDiff = sumDiff.val[0] / (diffMat.rows() * diffMat.cols() * 255.0);

                    // Standard deviation (local fluctuations)
                    Mat diffFloat = new Mat();
                    diffMat.convertTo(diffFloat, CvType.CV_32F, 1.0 / 255.0);
                    Scalar meanVal = Core.mean(diffFloat);
                    Mat meanMat = new Mat(diffFloat.size(), CvType.CV_32F, new Scalar(meanVal.val[0]));
                    Mat varianceMat = new Mat();
                    Core.subtract(diffFloat, meanMat, varianceMat);
                    Core.multiply(varianceMat, varianceMat, varianceMat);
                    double stdDev = Math.sqrt(Core.sumElems(varianceMat).val[0] / (diffFloat.rows() * diffFloat.cols()));

                    double depthMapDifference = Math.max(meanDiff, stdDev);

                    // --- Determine smoothing factor ---
                    double smoothing;
                    if (depthMapDifference > DEPTH_DIFF_THRESHOLD) {
                        smoothing = MAX_SMOOTHING; // fully adopt large changes
                    } else if(depthMapDifference > 0.01){
                        smoothing = depthMapDifference; // proportional to difference
                        smoothing = Math.max(MIN_SMOOTHING, Math.min(MAX_SMOOTHING, smoothing));
                    } else {
                        // Prevents mini pixel shifts reducing sharpness on still images
                        smoothing = 0;
                    }

                    // --- Apply smoothing ---
                    Imgproc.accumulateWeighted(rawFloat, previousSmoothedMat, smoothing);

                    // --- Normalize for shader output ---
                    Mat normalizedForShader = new Mat();
                    Core.MinMaxLocResult mmr = Core.minMaxLoc(previousSmoothedMat);
                    Core.subtract(previousSmoothedMat, new Scalar(mmr.minVal), normalizedForShader);
                    Core.divide(normalizedForShader, new Scalar(mmr.maxVal - mmr.minVal + 1e-6), normalizedForShader);

                    Mat outputMat = new Mat();
                    normalizedForShader.convertTo(outputMat, CvType.CV_8U, 255.0);
                    outputMat.get(0, 0, resultBuffer.array());

                    latestDepthMap.set(resultBuffer);

                    // --- Cleanup ---
                    diffMat.release();
                    diffFloat.release();
                    meanMat.release();
                    varianceMat.release();
                    normalizedForShader.release();
                    outputMat.release();

                } catch (Exception e) {
                    LimeLog.severe("AI exception " + e.getMessage());
                } finally {
                    if (rawMat != null) rawMat.release();
                    if (rawFloat != null) rawFloat.release();
                    if (resultBuffer != null) freeSmoothedBuffers.offer(resultBuffer);
                    if (result != null) {
                        freeInputBuffers.offer(result.pixelBuffer);
                        freeOutputBuffers.offer(result.rawDepthBuffer);
                    }
                    long duration = (System.nanoTime() - startTime) / 1_000_000;
                    Log.d("Stereo3DRenderer", "CalculateTime AiResult: " + duration + " ms");
                }
            }
            isAiResultHandlingRunning.set(false);
        }
    }

}