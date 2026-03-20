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
            String extension = tsSeqExt.length > 2 ? tsSeqExt[2] : "mp4";
            return new MediaFile(sessionId, dataType, timestamp, sequenceNumber, extension);
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
            String extension = parts.length > 2 ? parts[2] : "mp4";
            return new MediaFile(sessionId, dataType, timestamp, sequenceNumber, extension);
        } catch (Exception e) {
            Log.e(TAG, "Failed to reconstruct MediaFile from Drive data: " + fileName, e);
            return null;
        }
    }

    public String getExtension() {
        return extension != null ? extension : "mp4";
    }

    public String getMimeType() {
        if ("tsa".equalsIgnoreCase(getExtension())) {
            return "application/timestamp-reply";
        }
        return "video/mp4";
    }
}
