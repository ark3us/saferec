package net.ark3us.saferec.net;

import android.content.Context;
import android.util.Log;

import net.ark3us.saferec.misc.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The list of what a session recorded, kept on the phone and uploaded next to its chunks.
 *
 * Drive shows which chunks arrived, not which ones should have. One line per event, in plain
 * text a third party can read: a chunk got its first sample ("started"), was closed ("chunk"),
 * was repaired after a cut ("repaired", with its original) or recovered as it was ("cut"),
 * with sizes and SHA-256. A chunk that started but is not on Drive is a gap: still on the
 * phone, or lost. With timestamping on, the manifest is timestamped when a recording stops
 * and after a recovery, so a chunk removed from Drive later can be shown missing.
 *
 * The phone keeps the master (files/manifests/); after each line a copy goes to the outbox,
 * and Drive's copy is updated in place.
 */
public final class SessionManifest {
    private static final String TAG = SessionManifest.class.getSimpleName();
    private static final String DIR = "manifests";
    public static final String EXTENSION = "manifest";
    public static final String STARTED = "started";
    public static final String CHUNK = "chunk";
    public static final String REPAIRED = "repaired";
    public static final String CUT = "cut";

    private SessionManifest() {
    }

    // --- Lines (pure Java) ---

    static String startedLine(MediaFile chunk) {
        return STARTED + " " + chunk.sequenceNumber + " " + driveName(chunk);
    }

    static String chunkLine(String state, MediaFile chunk, long size, byte[] sha256) {
        return state + " " + chunk.sequenceNumber + " " + driveName(chunk) + " " + size + " sha256=" + hex(sha256);
    }

    static String repairedLine(MediaFile copy, long size, byte[] sha256, MediaFile original, long originalSize, byte[] originalSha256) {
        return chunkLine(REPAIRED, copy, size, sha256) + " original " + driveName(original) + " " + originalSize
                + " sha256=" + hex(originalSha256);
    }

    /**
     * What the manifest says about each sequence number: the last state of each chunk
     * ("started" when it never closed). Unknown lines are skipped.
     */
    public static Map<Integer, String> parse(String text) {
        Map<Integer, String> states = new TreeMap<>();
        for (String line : text.split("\n")) {
            String[] fields = line.trim().split(" ");
            if (fields.length < 3 || line.startsWith("#")) {
                continue;
            }
            String state = fields[0];
            if (!state.equals(STARTED) && !state.equals(CHUNK) && !state.equals(REPAIRED) && !state.equals(CUT)) {
                continue;
            }
            try {
                int seq = Integer.parseInt(fields[1]);
                // A late "started" (a repeated recovery) never hides how the chunk ended
                if (!state.equals(STARTED) || !states.containsKey(seq)) {
                    states.put(seq, state);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return states;
    }

    /** The chunks the manifest knows but Drive does not have, in order. */
    public static List<Integer> missing(Map<Integer, String> recorded, Collection<Integer> onDrive) {
        List<Integer> missing = new ArrayList<>();
        for (Integer seq : recorded.keySet()) {
            if (!onDrive.contains(seq)) {
                missing.add(seq);
            }
        }
        return missing;
    }

    /** Chunks the manifest knows but Drive lacks, split by whether this phone still holds them. */
    public static final class Gaps {
        public final List<Integer> onPhone = new ArrayList<>();
        public final List<Integer> lost = new ArrayList<>();

        public boolean isEmpty() {
            return onPhone.isEmpty() && lost.isEmpty();
        }
    }

    public static Gaps gaps(String manifest, Collection<Integer> onDrive, Collection<Integer> onPhone) {
        Gaps gaps = new Gaps();
        for (Integer seq : missing(parse(manifest), onDrive)) {
            (onPhone.contains(seq) ? gaps.onPhone : gaps.lost).add(seq);
        }
        return gaps;
    }

    private static String driveName(MediaFile file) {
        return file.timestamp + "." + file.sequenceNumber + "." + file.getExtension();
    }

    static String hex(byte[] bytes) {
        if (bytes == null) {
            return "unknown";
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    /** The outbox copy's name: the session id stands for timestamp and 0 for sequence. */
    static String copyName(String sessionId, String dataType) {
        return sessionId + "_" + dataType + "_" + sessionId + ".0." + EXTENSION;
    }

    // --- On the phone ---

    /** The first sample went into this chunk. */
    public static void started(Context context, File chunk) {
        MediaFile mediaFile = MediaFile.fromFile(chunk);
        if (mediaFile != null) {
            append(context, mediaFile, startedLine(mediaFile));
        }
    }

    /** The chunk was closed; sha256 may be null when it could not be read. */
    public static void closed(Context context, File chunk, long size, byte[] sha256) {
        MediaFile mediaFile = MediaFile.fromFile(chunk);
        if (mediaFile != null) {
            append(context, mediaFile, chunkLine(CHUNK, mediaFile, size, sha256));
        }
    }

    /** A cut chunk was recovered: repaired (copy and original), or as it was (copy null). */
    public static void recovered(Context context, File copy, byte[] copySha256, File original, byte[] originalSha256) {
        MediaFile chunk = MediaFile.fromFile(original);
        if (chunk == null) {
            return;
        }
        if (copy == null) {
            append(context, chunk, chunkLine(CUT, chunk, original.length(), originalSha256));
        } else {
            MediaFile originalName = new MediaFile(chunk.sessionId, chunk.dataType, chunk.timestamp,
                    chunk.sequenceNumber, "cut");
            append(context, chunk, repairedLine(chunk, copy.length(), copySha256, originalName, original.length(), originalSha256));
        }
    }

    private static File master(Context context, String sessionId, String dataType) {
        return new File(new File(context.getFilesDir(), DIR), sessionId + "_" + dataType + ".txt");
    }

    /**
     * Appends a line to the master and sends a copy. Recording never depends on it: a
     * failure is logged and the manifest just misses that line.
     */
    private static synchronized void append(Context context, MediaFile chunk, String line) {
        File master = master(context, chunk.sessionId, chunk.dataType);
        try {
            File dir = master.getParentFile();
            boolean fresh = !master.exists();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create " + dir);
            }
            try (FileOutputStream out = new FileOutputStream(master, true)) {
                if (fresh) {
                    out.write(("# SafeRec session " + chunk.sessionId + ", " + chunk.dataType
                            + ": started, chunk, repaired or cut, sequence, Drive name, bytes, SHA-256\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            publish(context, chunk.sessionId, chunk.dataType, master);
        } catch (IOException e) {
            Log.w(TAG, "Manifest not updated: " + line, e);
        }
    }

    /** Copies the master to the outbox (atomic rename) and queues it. */
    private static void publish(Context context, String sessionId, String dataType, File master) throws IOException {
        File outbox = GoogleDriveFileUploader.outboxDir(context);
        if (!outbox.isDirectory() && !outbox.mkdirs()) {
            throw new IOException("Cannot create the outbox");
        }
        File tmp = new File(context.getCacheDir(), copyName(sessionId, dataType) + ".tmp");
        Files.write(tmp.toPath(), Files.readAllBytes(master.toPath()));
        File copy = new File(outbox, copyName(sessionId, dataType));
        if (!tmp.renameTo(copy)) {
            tmp.delete();
            throw new IOException("Cannot move the manifest to the outbox");
        }
        GoogleDriveFileUploader.getInstance(context).upload(copy);
    }

    /**
     * With timestamping on, timestamps the manifest as it is now (a recording stopped, or a
     * recovery added lines). Blocking: call it off the main thread. Not retried on failure.
     */
    public static void timestamp(Context context, String sessionId, String dataType) {
        if (!Settings.isTimestampingEnabled(context)) {
            return;
        }
        File master = master(context, sessionId, dataType);
        byte[] hash;
        // Read under the lock, ask the TSA outside it: new lines never wait for the network
        synchronized (SessionManifest.class) {
            if (!master.exists()) {
                return;
            }
            try {
                hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(master.toPath()));
            } catch (IOException | NoSuchAlgorithmException e) {
                Log.w(TAG, "Cannot hash the manifest of session " + sessionId, e);
                return;
            }
        }
        File token = new File(GoogleDriveFileUploader.outboxDir(context), copyName(sessionId, dataType) + ".tsa");
        if (TsaClient.timestampHash(context, hash, token, false)) {
            GoogleDriveFileUploader.getInstance(context).upload(token);
        } else {
            Log.w(TAG, "No timestamp for the manifest of session " + sessionId);
        }
    }

    /** True for the files Drive keeps one copy of, updated in place: the manifest and its timestamp. */
    public static boolean isUpdatedInPlace(MediaFile file) {
        String extension = file.getExtension();
        return extension.equals(EXTENSION) || extension.equals(EXTENSION + ".tsa");
    }
}
