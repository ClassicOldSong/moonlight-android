package com.limelight.utils;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

public class ReflectivePaddingInt8Minimal {

    /**
     * In-place Reflected Padding + Feather + Blur auf ByteBuffer (INT8 RGB)
     * Minimaler Speicher: kein alpha3 Merge, nur 2 temporäre Mats
     */
    public static void applyReflectedPadding(ByteBuffer buffer) {
        final int size = 256;
        final int band = (int)(size * 0.1); // obere/untere 20%
        final int featherPx = 12;
        final int blurKsize = 7;

        // --- ByteBuffer -> Mat (CV_8UC3) ---
        buffer.rewind();
        byte[] arr = new byte[size * size * 3];
        buffer.get(arr);
        Mat mat = new Mat(size, size, CvType.CV_8UC3);
        mat.put(0, 0, arr);

        // --- Top-Band ---
        Mat topBand = mat.submat(band, 2*band, 0, size);
        Mat tmp = new Mat();
        Core.flip(topBand, tmp, 0);
        Imgproc.GaussianBlur(tmp, tmp, new Size(blurKsize, blurKsize), 0);
        blendInt8Minimal(mat.submat(0, band, 0, size), tmp, band, featherPx);
        tmp.release();
        topBand.release();

        // --- Bottom-Band ---
        Mat botBand = mat.submat(size - 2*band, size - band, 0, size);
        tmp = new Mat();
        Core.flip(botBand, tmp, 0);
        Imgproc.GaussianBlur(tmp, tmp, new Size(blurKsize, blurKsize), 0);
        blendInt8Minimal(mat.submat(size - band, size, 0, size), tmp, band, featherPx);
        tmp.release();
        botBand.release();

        // --- Mat -> ByteBuffer zurück ---
        Core.flip(mat, mat, 0);
        mat.get(0,0,arr);
        buffer.rewind();
        buffer.put(arr);
        buffer.rewind();
        mat.release();
    }

    /**
     * INT8 Alpha-Blend ohne Merge: dst = (alpha*padded + (255-alpha)*dst)/255
     * alpha linear von 0-255 über band Pixel
     */
    private static void blendInt8Minimal(Mat dst, Mat padded, int band, int featherPx) {
        int width = dst.cols();
        int channels = dst.channels();
        byte[] dstRow = new byte[width * channels];
        byte[] padRow = new byte[width * channels];

        for(int y=0; y<band; y++){
            int alpha = Math.min(255, (y*255)/featherPx);
            int invAlpha = 255 - alpha;

            dst.get(y,0,dstRow);
            padded.get(y,0,padRow);

            for(int i=0; i<dstRow.length; i++){
                int val = (alpha*(padRow[i]&0xFF) + invAlpha*(dstRow[i]&0xFF))/255;
                dstRow[i] = (byte)val;
            }

            dst.put(y,0,dstRow);
        }
    }
}
