package net.ark3us.saferec.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drive thumbnails for an image view: the placeholder at once, the picture when it arrives.
 * One per activity. A failure keeps the placeholder: a missing picture is never an error.
 */
public final class ThumbnailLoader {
    private static final String TAG = ThumbnailLoader.class.getSimpleName();
    private static final int TIMEOUT_MS = 10_000;
    private static final int FALLBACK_SIZE_PX = 256;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    // 1/16 of the heap, counted in bytes
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) Math.max(1024 * 1024, Runtime.getRuntime().maxMemory() / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public ThumbnailLoader(Context context) {
        // Nothing to keep from the context: the views arrive with each load
    }

    /**
     * Shows {@code placeholder} now, then the picture of {@code thumbnailLink}. The view is
     * tagged with the link it waits for, so a slow answer never lands on a view that moved on.
     */
    public void load(ImageView target, @Nullable String thumbnailLink, String accessToken,
                     @DrawableRes int placeholder) {
        target.setImageResource(placeholder);
        if (thumbnailLink == null) {
            target.setTag(null);
            return;
        }
        target.setTag(thumbnailLink);
        Bitmap cached = cache.get(thumbnailLink);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        int width = target.getWidth() > 0 ? target.getWidth() : FALLBACK_SIZE_PX;
        int height = target.getHeight() > 0 ? target.getHeight() : FALLBACK_SIZE_PX;
        try {
            executor.execute(() -> fetch(target, thumbnailLink, accessToken, width, height));
        } catch (RuntimeException e) {
            // Shut down: the activity is gone
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void fetch(ImageView target, String link, String accessToken, int width, int height) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(link).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Thumbnail answered HTTP " + connection.getResponseCode());
                return;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    bytes.write(buffer, 0, read);
                }
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) {
                Log.w(TAG, "Thumbnail could not be decoded");
                return;
            }
            cache.put(link, bitmap);
            main.post(() -> {
                if (link.equals(target.getTag())) {
                    target.setImageBitmap(bitmap);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Thumbnail failed: " + e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** The largest power of two that keeps the picture at least as big as the view. */
    static int sampleSize(int srcWidth, int srcHeight, int width, int height) {
        int sample = 1;
        while (srcWidth / (sample * 2) >= width && srcHeight / (sample * 2) >= height) {
            sample *= 2;
        }
        return sample;
    }
}
