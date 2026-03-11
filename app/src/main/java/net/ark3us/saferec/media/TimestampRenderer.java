package net.ark3us.saferec.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * OpenGL ES 2.0 pipeline that composites a date/time overlay onto camera
 * frames before passing them to the video encoder.
 *
 * <pre>
 *   Camera → [inputSurface] → GL render → [encoderSurface] → MediaCodec
 * </pre>
 *
 * Orientation: camera sensors always output landscape frames; the player
 * rotates them by {@code recordingRotation} (CW) for display. We define
 * the overlay position in "display NDC" and apply the inverse rotation
 * (+rotation in GL's CCW convention) to map it to raw-frame NDC. This
 * single rotation matrix handles both position and text orientation for
 * all four orientations (0/90/180/270).
 */
public class TimestampRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "TimestampRenderer";
    private static final float MARGIN = 0.03f;

    // --- EGL ---
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    // --- GL objects ---
    private int oesTextureId;
    private int overlayTextureId;
    private int cameraProgram;
    private int overlayProgram;
    private FloatBuffer quadVerts;

    // --- Surfaces ---
    private SurfaceTexture inputTexture;
    private Surface inputSurface;

    // --- State ---
    private volatile int rotation;
    private volatile boolean released;
    private final HandlerThread glThread;
    private final Handler glHandler;
    private final int frameWidth;
    private final int frameHeight;
    private final int bitmapWidth;
    private final int bitmapHeight;

    // Fixed texture-coordinate matrix: Y-flip only.
    // Camera2 delivers raw sensor-oriented (landscape) buffers to the
    // SurfaceTexture. We must NOT use getTransformMatrix() because it
    // includes the sensor rotation — that rotation is already handled by
    // setOrientationHint on the muxer. We only need the Y-flip to convert
    // from buffer coordinates (row 0 = top) to GL coordinates (t=0 = bottom).
    private static final float[] TEX_MATRIX = {
            1f,  0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f,  0f, 1f, 0f,
            0f,  1f, 0f, 1f,
    };

    // --- Text ---
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private long lastTextSecond = -1;

    // Interleaved quad: position (x, y) + texcoord (s, t), triangle-strip order
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
    };

    // @formatter:off
    private static final String CAMERA_VERT =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String CAMERA_FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    private static final String OVERLAY_VERT =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform mat4 uMVPMatrix;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);\n" +
            "}\n";

    private static final String OVERLAY_FRAG =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";
    // @formatter:on

    public TimestampRenderer(Surface encoderSurface, int width, int height,
                             int initialRotation) {
        frameWidth = width;
        frameHeight = height;
        rotation = initialRotation;
        bitmapHeight = Math.max(24, height / 12);
        bitmapWidth = bitmapHeight * 9;

        glThread = new HandlerThread("TimestampGL");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        glHandler.post(() -> {
            try {
                setupEGL(encoderSurface);
                setupGL();
            } catch (Exception e) {
                Log.e(TAG, "GL init failed", e);
            }
            latch.countDown();
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted waiting for GL init");
        }
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public void setRotation(int degrees) {
        rotation = degrees;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (!released) glHandler.post(this::drawFrame);
    }

    public void release() {
        released = true;
        CountDownLatch latch = new CountDownLatch(1);
        glHandler.post(() -> {
            teardownGL();
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        glThread.quitSafely();
    }

    // -----------------------------------------------------------------------
    // EGL / GL setup
    // -----------------------------------------------------------------------

    private void setupEGL(Surface output) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numCfg = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numCfg, 0);

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);

        int[] surfAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, configs[0], output, surfAttribs, 0);

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void setupGL() {
        ByteBuffer bb = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder());
        quadVerts = bb.asFloatBuffer();
        quadVerts.put(QUAD).position(0);

        int[] tex = new int[2];
        GLES20.glGenTextures(2, tex, 0);

        oesTextureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        applyTexParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);

        overlayTextureId = tex[1];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        applyTexParams(GLES20.GL_TEXTURE_2D);

        inputTexture = new SurfaceTexture(oesTextureId);
        inputTexture.setDefaultBufferSize(frameWidth, frameHeight);
        inputTexture.setOnFrameAvailableListener(this, glHandler);
        inputSurface = new Surface(inputTexture);

        cameraProgram = buildProgram(CAMERA_VERT, CAMERA_FRAG);
        overlayProgram = buildProgram(OVERLAY_VERT, OVERLAY_FRAG);

        GLES20.glViewport(0, 0, frameWidth, frameHeight);
    }

    // -----------------------------------------------------------------------
    // Per-frame rendering
    // -----------------------------------------------------------------------

    private void drawFrame() {
        if (released || inputTexture == null) return;
        try {
            inputTexture.updateTexImage();
        } catch (Exception e) {
            Log.w(TAG, "updateTexImage failed", e);
            return;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawCameraPass();
        refreshOverlayBitmap();
        drawOverlayPass();

        EGLExt.eglPresentationTimeANDROID(
                eglDisplay, eglSurface, inputTexture.getTimestamp());
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private void drawCameraPass() {
        GLES20.glUseProgram(cameraProgram);

        int aPos = GLES20.glGetAttribLocation(cameraProgram, "aPosition");
        int aTex = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord");
        int uST = GLES20.glGetUniformLocation(cameraProgram, "uSTMatrix");
        int uSamp = GLES20.glGetUniformLocation(cameraProgram, "uTexture");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(uSamp, 0);
        GLES20.glUniformMatrix4fv(uST, 1, false, TEX_MATRIX, 0);

        bindQuad(aPos, aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    private void drawOverlayPass() {
        GLES20.glUseProgram(overlayProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        int aPos = GLES20.glGetAttribLocation(overlayProgram, "aPosition");
        int aTex = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord");
        int uMVP = GLES20.glGetUniformLocation(overlayProgram, "uMVPMatrix");
        int uSamp = GLES20.glGetUniformLocation(overlayProgram, "uTexture");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        GLES20.glUniform1i(uSamp, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, computeOverlayMVP(), 0);

        bindQuad(aPos, aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /**
     * Maps the overlay from "display NDC" (where -1..1 spans the
     * <em>displayed</em> width/height) to raw-frame NDC by rotating
     * +rotation degrees (CCW in GL = inverse of the player's CW rotation).
     * Because display NDC already accounts for the dimension swap at
     * 90/270, no aspect-ratio correction is needed.
     */
    private float[] computeOverlayMVP() {
        int rot = rotation;
        boolean portrait = (rot == 90 || rot == 270);
        float dw = portrait ? frameHeight : frameWidth;
        float dh = portrait ? frameWidth : frameHeight;

        float qw = bitmapWidth / (dw / 2f);
        float qh = bitmapHeight / (dh / 2f);
        if (qw > 0.85f) {
            float scale = 0.85f / qw;
            qw *= scale;
            qh *= scale;
        }
        float cx = -1f + MARGIN + qw / 2f;
        float cy = -1f + MARGIN + qh / 2f;

        float[] model = new float[16];
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, cx, cy, 0f);
        Matrix.scaleM(model, 0, qw / 2f, qh / 2f, 1f);

        float[] rotMat = new float[16];
        Matrix.setIdentityM(rotMat, 0);
        Matrix.rotateM(rotMat, 0, rot, 0f, 0f, 1f);

        float[] mvp = new float[16];
        Matrix.multiplyMM(mvp, 0, rotMat, 0, model, 0);
        return mvp;
    }

    /** Re-renders the text bitmap only when the wall-clock second changes. */
    private void refreshOverlayBitmap() {
        long sec = System.currentTimeMillis() / 1000;
        if (sec == lastTextSecond) return;
        lastTextSecond = sec;

        Bitmap bmp = Bitmap.createBitmap(
                bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0x80000000);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(bitmapHeight * 0.7f);
        p.setTypeface(Typeface.MONOSPACE);

        String text = dateFmt.format(new Date());
        float tw = p.measureText(text);
        c.drawText(text, (bitmapWidth - tw) / 2f, bitmapHeight * 0.78f, p);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    private void teardownGL() {
        if (inputTexture != null) {
            inputTexture.setOnFrameAvailableListener(null);
            inputTexture.release();
            inputTexture = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (cameraProgram != 0) GLES20.glDeleteProgram(cameraProgram);
        if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram);
        GLES20.glDeleteTextures(2, new int[]{oesTextureId, overlayTextureId}, 0);

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE)
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    // -----------------------------------------------------------------------
    // GL utilities
    // -----------------------------------------------------------------------

    private void bindQuad(int posLoc, int tcLoc) {
        quadVerts.position(0);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(posLoc);
        quadVerts.position(2);
        GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(tcLoc);
    }

    private static void applyTexParams(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int buildProgram(String vert, String frag) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vert);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, frag);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Program link failed: " + log);
        }
        return prog;
    }

    private static int loadShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }
}
