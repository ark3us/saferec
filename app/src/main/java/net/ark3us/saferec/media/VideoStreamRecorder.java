package net.ark3us.saferec.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.ark3us.saferec.misc.Settings;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class VideoStreamRecorder {
    public interface Callback {
        void onStarted(boolean success);
    }

    public static class Quality {
        public final int width;
        public final int height;
        public final int bitRate;
        public final int frameRate;

        public Quality(int width, int height, int bitRate, int frameRate) {
            this.width = width;
            this.height = height;
            this.bitRate = bitRate;
            this.frameRate = frameRate;
        }

        public int getRecommendedChunkSize() {
            // Aim for roughly 10 seconds of video per chunk
            return (bitRate / 8) * 10;
        }

        public static final Quality HIGH = new Quality(1280, 720, 2000000, 30);
        public static final Quality MEDIUM = new Quality(854, 480, 1000000, 30);
        public static final Quality LOW = new Quality(640, 360, 500000, 30);

        public static Quality getByName(String name) {
            switch (name) {
                case "HIGH":
                    return HIGH;
                case "MEDIUM":
                    return MEDIUM;
                case "LOW":
                default:
                    return LOW;
            }
        }
    }

    private static final String TAG = VideoStreamRecorder.class.getSimpleName();
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int I_FRAME_INTERVAL = 1;

    private MediaCodec encoder;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface encoderSurface;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Surface previewSurface;
    private CameraManager manager;

    private volatile MuxerSink muxer;
    private Callback callback;
    private Quality quality;
    private Size previewSize;
    private volatile boolean isRecording = false;
    private boolean useFront = false;
    private int sensorOrientationDeg;
    private int recordingRotation;
    private DisplayManager.DisplayListener rotationListener;
    private TimestampRenderer timestampRenderer;
    private boolean surveillanceMode = false;

    private static VideoStreamRecorder instance;

    private VideoStreamRecorder() {
        this.quality = Quality.LOW;
    }
    public void syncQuality(Context context) {
        this.quality = Quality.getByName(Settings.getVideoQuality(context));
        Log.i(TAG, "Quality synced to: " + quality.width + "x" + quality.height);
    }

    public Quality getQuality() {
        return quality;
    }

    public static VideoStreamRecorder getInstance() {
        if (instance == null) {
            instance = new VideoStreamRecorder();
        }
        return instance;
    }

    public static String getBackCameraId(Context context) {
        return getCameraId(context, CameraCharacteristics.LENS_FACING_BACK);
    }

    public static String getFrontCameraId(Context context) {
        return getCameraId(context, CameraCharacteristics.LENS_FACING_FRONT);
    }

    /**
     * Starts a preview-only session (no encoding).
     */
    public boolean startPreview(Context context, SurfaceTexture surfaceTexture, boolean useFront,
            @Nullable Callback callback) {
        Log.i(TAG, "startPreview(useFront=" + useFront + ")");
        try {
            String cameraId = initCamera(context, useFront);
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(surfaceTexture);
            this.callback = callback;
            return openCamera(context, cameraId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start preview", e);
            if (callback != null)
                callback.onStarted(false);
            return false;
        }
    }

    /**
     * Corrects preview orientation when device rotates (with configChanges).
     * Standard Camera2Basic formula — only transforms for landscape/upside-down.
     */
    public void configureTransform(TextureView textureView) {
        if (textureView == null || previewSize == null)
            return;
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth == 0 || viewHeight == 0)
            return;

        int rotation = textureView.getDisplay().getRotation();
        Matrix matrix = new Matrix();
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            // Landscape: buffer and view are both landscape-ish
            float scaleX = (float) viewWidth / previewSize.getWidth();
            float scaleY = (float) viewHeight / previewSize.getHeight();
            float scale = Math.max(scaleX, scaleY);
            // The TextureView maps buffer to view directly; we need to undo the default
            // stretch and apply uniform scale
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else {
            // Portrait (0° or 180°): buffer is landscape but view is portrait
            // The camera sensor delivers landscape frames; TextureView stretches them
            // to fill the portrait view. We undo that stretch and apply uniform scale.

            float bufferWidth = previewSize.getWidth(); // e.g. 1280
            float bufferHeight = previewSize.getHeight(); // e.g. 720
            // After the implicit 90° rotation the effective size in view space is (height x
            // width)
            float effectiveWidth = bufferHeight; // 720
            float effectiveHeight = bufferWidth; // 1280
            float scaleX = (float) viewWidth / effectiveWidth;
            float scaleY = (float) viewHeight / effectiveHeight;
            float scale = Math.max(scaleX, scaleY); // center-crop
            matrix.postScale(
                    effectiveWidth * scale / viewWidth,
                    effectiveHeight * scale / viewHeight,
                    centerX, centerY);

            if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180, centerX, centerY);
            }
        }

        textureView.setTransform(matrix);
    }

    /**
     * Starts recording: sets up encoder and camera, feeds encoded data to the
     * muxer.
     * If surfaceTexture is provided, the preview will also be shown during
     * recording.
     */
    public boolean start(Context context, MuxerSink muxer, boolean useFront,
            @Nullable SurfaceTexture surfaceTexture, @Nullable Callback callback) {
        Log.i(TAG, "start(useFront=" + useFront + ", hasSurfaceTexture=" + (surfaceTexture != null) + ")");
        this.surveillanceMode = Settings.isSurveillanceMode(context);
        try {
            String cameraId = initCamera(context, useFront);
            if (surfaceTexture != null) {
                Log.i(TAG, "Setting up preview surface for recording");
                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                previewSurface = new Surface(surfaceTexture);
            }
            setupEncoder(context, cameraId);
            this.callback = callback;
            this.muxer = muxer;
            if (!surveillanceMode) {
                registerRotationListener(context);
            }
            return openCamera(context, cameraId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up encoder or open camera", e);
            return false;
        }
    }

    public void stopRecording() {
        Log.i(TAG, "stopRecording() called. Current recording state: " + isRecording);
        isRecording = false;
        unregisterRotationListener();
        releaseTimestampRenderer();
        releaseEncoder();
        this.muxer = null;
        this.surveillanceMode = false;

        if (previewSurface != null) {
            Log.i(TAG, "Preview attached, rebuilding capture session without encoder");
            this.callback = null;
            startCaptureSession();
        } else {
            Log.i(TAG, "No preview attached, fully stopping");
            stop();
        }
    }

    public void stop() {
        Log.i(TAG, "stop() called - current recording state: " + isRecording);
        isRecording = false;
        unregisterRotationListener();
        closeSafely(captureSession);
        captureSession = null;
        closeSafely(cameraDevice);
        cameraDevice = null;
        releaseEncoder();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        stopCameraThread();
        Log.i(TAG, "VideoStreamRecorder stop completed");
    }

    /**
     * Detaches the preview surface from the capture session without stopping
     * the recording. Call this when the activity goes away mid-recording so the
     * now-invalid preview surface doesn't crash the capture session.
     */
    public void detachPreview() {
        if (!isRecording) {
            Log.w(TAG, "detachPreview called but not recording, ignoring");
            return;
        }
        previewSurface = null;
        if (surveillanceMode && timestampRenderer != null) {
            Log.i(TAG, "Surveillance mode: detaching preview via renderer");
            timestampRenderer.setPreviewSurface(null);
        } else {
            Log.i(TAG, "Preview detached, rebuilding capture session without preview");
            startCaptureSession();
        }
    }

    /**
     * Attaches a preview surface to an ongoing recording session.
     * Rebuilds the capture session to include both encoder and preview targets.
     */
    public void attachPreview(SurfaceTexture surfaceTexture) {
        if (!isRecording) {
            Log.w(TAG, "attachPreview called but not recording, ignoring");
            return;
        }
        if (previewSize != null) {
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        }
        previewSurface = new Surface(surfaceTexture);
        if (surveillanceMode && timestampRenderer != null) {
            Log.i(TAG, "Surveillance mode: attaching preview via renderer");
            timestampRenderer.setPreviewSurface(previewSurface);
        } else {
            Log.i(TAG, "Preview attached, rebuilding capture session with preview");
            startCaptureSession();
        }
    }

    // --- Private helpers ---

    /**
     * Common init shared by startPreview and start: syncs quality, sets up
     * the camera thread, resolves the camera ID, and picks a preview size.
     */
    private String initCamera(Context context, boolean useFront) {
        syncQuality(context);
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        ensureCameraThread();
        String cameraId = useFront ? getFrontCameraId(context) : getBackCameraId(context);
        this.useFront = useFront;
        previewSize = choosePreviewSize(context, cameraId);
        Log.i(TAG, "Chosen preview size: " + previewSize);
        return cameraId;
    }

    private void releaseEncoder() {
        releaseTimestampRenderer();
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception e) {
                Log.w(TAG, "Encoder stop failed: " + e.getMessage());
            }
            try {
                encoder.release();
            } catch (Exception e) {
                Log.w(TAG, "Encoder release failed: " + e.getMessage());
            }
        }
        encoder = null;
        encoderSurface = null;
    }

    private void releaseTimestampRenderer() {
        if (timestampRenderer != null) {
            timestampRenderer.release();
            timestampRenderer = null;
        }
    }

    private DisplayManager displayManager;

    private void registerRotationListener(Context context) {
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) return;
        rotationListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY) return;
                MuxerSink localMuxer = muxer;
                if (localMuxer == null) return;
                try {
                    Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                    if (display == null) return;
                    int newDeviceRotation = display.getRotation() * 90;
                    int newRotation = (sensorOrientationDeg - newDeviceRotation + 360) % 360;
                    if (newRotation != recordingRotation) {
                        Log.i(TAG, "Device rotated: " + recordingRotation + "° → " + newRotation + "°");
                        recordingRotation = newRotation;
                        localMuxer.updateVideoRotation(newRotation);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error checking rotation", e);
                }
            }

            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}
        };
        displayManager.registerDisplayListener(rotationListener, cameraHandler);
        Log.i(TAG, "Rotation listener registered");
    }

    private void unregisterRotationListener() {
        if (rotationListener != null && displayManager != null) {
            displayManager.unregisterDisplayListener(rotationListener);
        }
        rotationListener = null;
        displayManager = null;
        Log.i(TAG, "Rotation listener unregistered");
    }

    private static String getCameraId(Context context, int lensFacing) {
        try {
            CameraManager mgr = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null)
                return null;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == lensFacing)
                    return id;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get camera id", e);
        }
        return null;
    }

    private Size choosePreviewSize(Context context, String cameraId) {
        try {
            CameraManager mgr = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null)
                return new Size(quality.width, quality.height);
            StreamConfigurationMap map = mgr.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null)
                return new Size(quality.width, quality.height);
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0)
                return new Size(quality.width, quality.height);

            // Pick the size closest to the recording quality
            int targetPixels = quality.width * quality.height;
            Size best = sizes[0];
            int bestDiff = Math.abs(best.getWidth() * best.getHeight() - targetPixels);
            for (Size s : sizes) {
                int diff = Math.abs(s.getWidth() * s.getHeight() - targetPixels);
                if (diff < bestDiff) {
                    best = s;
                    bestDiff = diff;
                }
            }
            return best;
        } catch (Exception e) {
            return new Size(quality.width, quality.height);
        }
    }

    private int getSensorOrientation(String cameraId) {
        try {
            Integer orientation = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            Log.i(TAG, "Camera sensor orientation: " + orientation);
            return orientation != null ? orientation : 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get sensor orientation", e);
            return 0;
        }
    }

    private int getDeviceRotationDegrees(Context context) {
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            return display.getRotation() * 90;
        } catch (Exception e) {
            return 0;
        }
    }

    private void setupEncoder(Context context, String cameraId) throws Exception {
        sensorOrientationDeg = getSensorOrientation(cameraId);
        int deviceRotation = getDeviceRotationDegrees(context);

        if (surveillanceMode) {
            // Surveillance mode: TimestampRenderer handles rotation internally,
            // so the encoded frames are already upright → no rotation hint needed.
            recordingRotation = 0;
            Log.i(TAG, "Surveillance mode: recordingRotation forced to 0");
        } else {
            recordingRotation = (sensorOrientationDeg - deviceRotation + 360) % 360;
            Log.i(TAG, "Recording rotation: " + recordingRotation + "° (sensor=" + sensorOrientationDeg + ", device="
                    + deviceRotation + ")");
        }

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, quality.width, quality.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, quality.bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();

        encoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                    @NonNull MediaCodec.BufferInfo info) {
                MuxerSink localMuxer = muxer;
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0 && localMuxer != null) {
                    localMuxer.writeVideoData(buffer, info);
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "Encoder error", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat fmt) {
                Log.i(TAG, "Encoder output format changed: " + fmt);
                MuxerSink localMuxer = muxer;
                if (localMuxer != null) {
                    localMuxer.addVideoTrack(fmt, recordingRotation);
                }
            }
        }, cameraHandler);

        encoder.start();

        if (surveillanceMode) {
            // stMatrix rotates camera from sensor-native (landscape) to
            // device-natural (portrait). The encoder MVP counter-rotates
            // by -deviceRotation; the preview uses identity (TextureView
            // transform handles it).
            Log.i(TAG, "Surveillance mode: creating TimestampRenderer (deviceRotation=" + deviceRotation + ")");
            timestampRenderer = new TimestampRenderer(
                    encoderSurface, previewSurface,
                    quality.width, quality.height,
                    deviceRotation, cameraHandler);
        }
    }

    private boolean openCamera(Context context, String cameraId) {
        if (context.checkSelfPermission(
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            if (callback != null)
                callback.onStarted(false);
            return false;
        }
        if (cameraDevice != null) {
            if (cameraId.equals(cameraDevice.getId())) {
                return startCaptureSession();
            } else {
                Log.i(TAG, "Switching camera: closing old device " + cameraDevice.getId() + " to open " + cameraId);
                closeSafely(captureSession);
                captureSession = null;
                closeSafely(cameraDevice);
                cameraDevice = null;
            }
        }
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    if (callback != null)
                        callback.onStarted(false);
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    if (callback != null)
                        callback.onStarted(false);
                }

                @Override
                public void onClosed(@NonNull CameraDevice camera) {
                    Log.i(TAG, "Camera closed");
                }
            }, cameraHandler);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
            if (callback != null)
                callback.onStarted(false);
            return false;
        }
    }

    private boolean startCaptureSession() {
        if (cameraDevice == null) {
            Log.w(TAG, "startCaptureSession: cameraDevice is null, skipping");
            return false;
        }
        try {
            closeSafely(captureSession);
            captureSession = null;

            boolean hasEncoder = encoderSurface != null;
            int template = hasEncoder ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            final CaptureRequest.Builder localBuilder = cameraDevice.createCaptureRequest(template);
            localBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            List<Surface> targets = new ArrayList<>();

            if (surveillanceMode && timestampRenderer != null && hasEncoder) {
                // In surveillance mode the camera feeds into the renderer's surface;
                // the renderer draws to both encoder and preview EGL surfaces.
                Surface cameraSurface = timestampRenderer.getCameraSurface();
                if (cameraSurface != null) {
                    localBuilder.addTarget(cameraSurface);
                    targets.add(cameraSurface);
                }
            } else {
                if (hasEncoder) {
                    localBuilder.addTarget(encoderSurface);
                    targets.add(encoderSurface);
                }
                if (previewSurface != null) {
                    localBuilder.addTarget(previewSurface);
                    targets.add(previewSurface);
                }
            }

            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(localBuilder.build(), null, cameraHandler);
                        isRecording = muxer != null;
                        if (callback != null)
                            callback.onStarted(true);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start capture session", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Capture session configuration failed");
                    isRecording = false;
                    if (callback != null)
                        callback.onStarted(false);
                }
            }, cameraHandler);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create capture session", e);
            return false;
        }
    }

    private void ensureCameraThread() {
        if (cameraThread != null)
            return;
        cameraThread = new HandlerThread("CameraBackground");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null)
            return;
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private static void closeSafely(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}