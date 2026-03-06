package net.ark3us.saferec.net;

import android.util.Log;

import java.io.File;

public class MediaFile {
    private static final String TAG = MediaFile.class.getSimpleName();
    public String sessionId;
    public String dataType;
    public long timestamp;
    public int sequenceNumber;

    public String getFileName() {
        return String.format("%s_%s_%d.%d", sessionId, dataType, timestamp, sequenceNumber);
    }

    public MediaFile(String sessionId, String dataType, long timestamp, int sequenceNumber) {
        this.sessionId = sessionId;
        this.dataType = dataType;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
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
            String[] tsSeq = parts[2].split("\\.");
            if (tsSeq.length != 2) {
                Log.e(TAG, "Invalid file name: " + name);
                return null;
            }
            long timestamp = Long.parseLong(tsSeq[0]);
            int sequenceNumber = Integer.parseInt(tsSeq[1]);
            return new MediaFile(sessionId, dataType, timestamp, sequenceNumber);
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
            return new MediaFile(sessionId, dataType, timestamp, sequenceNumber);
        } catch (Exception e) {
            Log.e(TAG, "Failed to reconstruct MediaFile from Drive data: " + fileName, e);
            return null;
        }
    }

    public String getExtension() {
        return "mp4";
    }

    public String getMimeType() {
        return "video/mp4";
    }
}
