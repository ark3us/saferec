package net.ark3us.saferec.net;

import android.util.Log;

import java.io.File;
import java.util.Locale;

public class MediaFile {
    private static final String TAG = MediaFile.class.getSimpleName();
    public String sessionId;
    public String dataType;
    public long timestamp;
    public int sequenceNumber;
    public String extension; // added field for extension

    public String getFileName() {
        return String.format(Locale.ROOT, "%s_%s_%d.%d.%s",
                sessionId, dataType, timestamp, sequenceNumber, extension != null ? extension : "mp4");
    }

    public MediaFile(String sessionId, String dataType, long timestamp, int sequenceNumber, String extension) {
        this.sessionId = sessionId;
        this.dataType = dataType;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        this.extension = extension;
    }

    public MediaFile(String sessionId, String dataType, long timestamp, int sequenceNumber) {
        this(sessionId, dataType, timestamp, sequenceNumber, "mp4");
    }

    public static MediaFile fromFile(File file) {
        String name = file.getName();
        try {
            String[] parts = name.split("_");
            if (parts.length != 3) {
                Log.e(TAG, "Invalid file name: " + name);
                return null;
            }
            String sessionId = parts[0];
            String dataType = parts[1];
            String[] tsSeqExt = parts[2].split("\\.");
            if (tsSeqExt.length < 2) {
                Log.e(TAG, "Invalid file name: " + name);
                return null;
            }
            long timestamp = Long.parseLong(tsSeqExt[0]);
            int sequenceNumber = Integer.parseInt(tsSeqExt[1]);
            return new MediaFile(sessionId, dataType, timestamp, sequenceNumber, extensionOf(tsSeqExt));
        } catch (Exception e) {
            Log.e(TAG, "Invalid file name: " + name, e);
            return null;
        }
    }

    public static MediaFile fromDriveData(String sessionId, String dataType, String fileName) {
        try {
            // fileName is expected to be "timestamp.sequenceNumber.ext"
            String[] parts = fileName.split("\\.");
            if (parts.length < 2) return null;
            long timestamp = Long.parseLong(parts[0]);
            int sequenceNumber = Integer.parseInt(parts[1]);
            return new MediaFile(sessionId, dataType, timestamp, sequenceNumber, extensionOf(parts));
        } catch (Exception e) {
            Log.e(TAG, "Failed to reconstruct MediaFile from Drive data: " + fileName, e);
            return null;
        }
    }

    /**
     * Everything after "timestamp.sequence": "mp4", "tsa", "cut" or "cut.tsa" (the timestamp of
     * a cut original). Taking only the first part made "cut.tsa" a "cut", uploaded under the
     * very name of the original it certifies.
     */
    private static String extensionOf(String[] tsSeqExt) {
        if (tsSeqExt.length <= 2) {
            return "mp4";
        }
        return String.join(".", java.util.Arrays.copyOfRange(tsSeqExt, 2, tsSeqExt.length));
    }

    /** RFC 3161 timestamp replies: a chunk's (".tsa") or a cut original's (".cut.tsa"). */
    public boolean isTimestamp() {
        String extension = getExtension().toLowerCase(Locale.ROOT);
        return extension.equals("tsa") || extension.endsWith(".tsa");
    }

    public String getExtension() {
        return extension != null ? extension : "mp4";
    }

    public String getMimeType() {
        if (isTimestamp()) {
            return "application/timestamp-reply";
        }
        if (SessionManifest.EXTENSION.equalsIgnoreCase(getExtension())) {
            return "text/plain"; // readable by anyone opening it on Drive
        }
        if ("cut".equalsIgnoreCase(getExtension())) {
            // The untouched original of a repaired chunk: an MP4 without its index, so no player
            // (nor Drive's preview) can open it. Not application/octet-stream: Drive takes that
            // for unknown, looks at the content and files it as video.
            return "application/x-saferec-cut";
        }
        return "video/mp4";
    }
}
