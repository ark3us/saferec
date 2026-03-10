package net.ark3us.saferec.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
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
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TimestampRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = TimestampRenderer.class.getSimpleName();

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewEglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private Surface encoderSurface;
    private Surface previewSurface;
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraSurface;
    private Handler handler;

    private int oesProgram;
    private int textProgram;

    private int cameraTextureId;
    private int textTextureId;

    private int width;
    private int height;

    private float[] cameraMvpMatrix = new float[16];
    private float[] textMvpMatrix = new float[16];
    private float[] stMatrix = new float[16];

    private Bitmap textBitmap;
    private Canvas textCanvas;
    private Paint textPaint;
    private SimpleDateFormat dateFormat;
    private long lastTextUpdate = 0;
    private float textSize;

    private boolean isReleased = false;

    private static final float[] VERTICES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
    };

    private static final float[] TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
    };

    private static final float[] TEX_COORDS_FLIPPED_Y = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private FloatBuffer texCoordBufferFlipped;

    public TimestampRenderer(Surface encoderSurface, Surface previewSurface, int width, int height, int deviceRotation, Handler handler) {
        this.encoderSurface = encoderSurface;
        this.previewSurface = previewSurface;
        this.width = width;
        this.height = height;
        this.handler = handler;

        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).position(0);

        texCoordBufferFlipped = ByteBuffer.allocateDirect(TEX_COORDS_FLIPPED_Y.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBufferFlipped.put(TEX_COORDS_FLIPPED_Y).position(0);

        Matrix.setIdentityM(cameraMvpMatrix, 0);
        // stMatrix rotates camera output from sensor-native to device-natural
        // orientation (portrait). For the encoder we counter-rotate so the
        // encoded pixels are landscape.  For the preview the TextureView's own
        // transform handles it, so we keep an identity matrix.
        Matrix.rotateM(cameraMvpMatrix, 0, 180 - deviceRotation, 0, 0, 1);

        Matrix.setIdentityM(textMvpMatrix, 0);

        handler.post(this::initGL);
    }

    public Surface getCameraSurface() {
        int attempts = 0;
        while (cameraSurface == null && attempts < 50) {
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            attempts++;
        }
        return cameraSurface;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setPreviewSurface(Surface surface) {
        this.previewSurface = surface;
        handler.post(() -> {
            if (isReleased) return;
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                previewEglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (surface != null && eglConfig != null) {
                int[] surfaceAttribs = { EGL14.EGL_NONE };
                previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0);
            }
        });
    }

    private void initGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        eglConfig = configs[0];

        int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

        int[] surfaceAttribs = { EGL14.EGL_NONE };
        if (encoderSurface != null) {
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, surfaceAttribs, 0);
        }
        if (previewSurface != null) {
            previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, previewSurface, surfaceAttribs, 0);
        }

        EGLSurface defaultSurface = eglSurface != EGL14.EGL_NO_SURFACE ? eglSurface : previewEglSurface;
        if (defaultSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, defaultSurface, defaultSurface, eglContext);
        }

        oesProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        textProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);

        cameraTextureId = createTextureOES();
        cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(width, height);
        cameraSurfaceTexture.setOnFrameAvailableListener(this, handler);
        cameraSurface = new Surface(cameraSurfaceTexture);

        textTextureId = createTexture2D();

        initTextRenderer();
    }

    private void initTextRenderer() {
        textBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        textCanvas = new Canvas(textBitmap);
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        
        textSize = Math.min(width, height) * 0.05f;
        textPaint.setTextSize(textSize);
        textPaint.setShadowLayer(5.0f, 0.0f, 0.0f, Color.BLACK);
        textPaint.setAntiAlias(true);
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    }

    private void updateTextTexture() {
        long now = System.currentTimeMillis();
        if (now - lastTextUpdate < 500) return;
        lastTextUpdate = now;

        textBitmap.eraseColor(Color.TRANSPARENT);

        String text = dateFormat.format(new Date(now));
        float textWidth = textPaint.measureText(text);
        
        textCanvas.drawText(text, width - textWidth - 20, height - 20, textPaint);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textBitmap, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (isReleased) return;

        // Ensure we have a context to update the texture
        EGLSurface defaultSurface = eglSurface != EGL14.EGL_NO_SURFACE ? eglSurface : previewEglSurface;
        if (defaultSurface == EGL14.EGL_NO_SURFACE) return;
        EGL14.eglMakeCurrent(eglDisplay, defaultSurface, defaultSurface, eglContext);

        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(stMatrix);
        long timestampNs = surfaceTexture.getTimestamp();

        updateTextTexture();

        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            renderScene(width, height, cameraMvpMatrix);
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }

        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext);
            int[] dim = new int[1];
            EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_WIDTH, dim, 0);
            int pw = dim[0];
            EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_HEIGHT, dim, 0);
            int ph = dim[0];
            // Use cameraMvpMatrix for preview too so output is already landscape
            renderScene(pw > 0 ? pw : width, ph > 0 ? ph : height, cameraMvpMatrix);
            EGLExt.eglPresentationTimeANDROID(eglDisplay, previewEglSurface, timestampNs);
            EGL14.eglSwapBuffers(eglDisplay, previewEglSurface);
        }
    }

    private void renderScene(int w, int h, float[] mvp) {
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(oesProgram);
        drawTexture(oesProgram, cameraTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stMatrix, texCoordBuffer, mvp);

        GLES20.glUseProgram(textProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        
        float[] identitySt = new float[16];
        Matrix.setIdentityM(identitySt, 0);

        drawTexture(textProgram, textTextureId, GLES20.GL_TEXTURE_2D, identitySt, texCoordBufferFlipped, textMvpMatrix);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawTexture(int program, int textureId, int textureTarget, float[] uvMatrix, FloatBuffer uvs, float[] mvp) {
        int uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        int uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix");

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, uvMatrix, 0);

        int aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        int aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord");

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);

        GLES20.glEnableVertexAttribArray(aTextureCoordLoc);
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, uvs);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(textureTarget, textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc);
        GLES20.glBindTexture(textureTarget, 0);
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private int createTextureOES() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return textures[0];
    }

    private int createTexture2D() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return textures[0];
    }

    public void release() {
        isReleased = true;
        handler.post(() -> {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            previewEglSurface = EGL14.EGL_NO_SURFACE;

            if (cameraSurface != null) cameraSurface.release();
            if (cameraSurfaceTexture != null) cameraSurfaceTexture.release();
            if (textBitmap != null) {
                textBitmap.recycle();
                textBitmap = null;
            }
        });
    }
}
