package net.ark3us.saferec.net;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip per session to hand over: a merged video to watch, the session's files exactly as
 * they were on Drive (laid out as Drive gives them, so the verifier runs on them unchanged),
 * a plain-language README, a report, and the verifier itself.
 *
 *     SafeRec-<session>/README.txt, report.txt, merged.mp4, verify_session.py
 *     SafeRec-<session>/evidence/<session>/<video|audio>/<the Drive files>
 *
 * Videos are stored, not compressed again: they are compressed already, and deflating them
 * costs time for nothing.
 */
public final class EvidencePackage {

    /** A session file downloaded from Drive, and its name there. */
    public static final class Item {
        public final File file;
        public final String driveName;

        public Item(File file, String driveName) {
            this.file = file;
            this.driveName = driveName;
        }
    }

    /** One chunk line of the report. */
    public static final class Row {
        final int seq;
        final String name;
        final long size;
        final String sha256;
        final String timestamp; // null when the chunk has none
        final String note; // null, or what to know about it (repaired, timestamp mismatch)

        public Row(int seq, String name, long size, String sha256, String timestamp, String note) {
            this.seq = seq;
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.timestamp = timestamp;
            this.note = note;
        }
    }

    /** The report's wording, in the app's language. */
    public static final class Labels {
        public String title, started, type, chunks, duration, manifest, gaps, none, columns, noTimestamp, footer;
    }

    private EvidencePackage() {
    }

    public static String rootName(String sessionId) {
        return "SafeRec-" + sessionId;
    }

    /**
     * Deletes the files older than maxAgeMs in dir: packages and merged videos already shared,
     * and the downloads and merges of an export or a merge cut short (a process that dies skips
     * its clean-up). A package of a long session weighs gigabytes, and Android clears the cache
     * only when space runs out. Not right after sharing: the receiving app may still be reading
     * it. Returns how many were deleted.
     */
    public static int deleteOld(File dir, long maxAgeMs, long nowMs) {
        File[] files = dir.listFiles(File::isFile);
        int deleted = 0;
        if (files != null) {
            for (File old : files) {
                if (nowMs - old.lastModified() > maxAgeMs && old.delete()) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    public static void write(File zip, String sessionId, String dataType, String readme, String report,
            File merged, List<Item> evidence, byte[] verifier) throws IOException {
        String root = rootName(sessionId) + "/";
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            deflated(out, root + "README.txt", readme.getBytes(StandardCharsets.UTF_8));
            deflated(out, root + "report.txt", report.getBytes(StandardCharsets.UTF_8));
            if (merged != null) {
                stored(out, root + "merged.mp4", merged);
            }
            for (Item item : evidence) {
                String path = root + "evidence/" + sessionId + "/" + dataType + "/" + item.driveName;
                if (item.driveName.endsWith(".mp4") || item.driveName.endsWith(".cut")) {
                    stored(out, path, item.file);
                } else {
                    deflated(out, path, readAll(item.file));
                }
            }
            deflated(out, root + "verify_session.py", verifier);
        }
    }

    /** check: what the phone found when it checked the session (SessionCheck), or null. */
    public static String report(Labels labels, String sessionId, String dataType, String started,
            String duration, String manifest, String gaps, String check, List<Row> rows) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(labels.title, sessionId)).append("\n\n");
        text.append(labels.started).append(": ").append(started).append('\n');
        text.append(labels.type).append(": ").append(dataType).append('\n');
        text.append(labels.chunks).append(": ").append(rows.size()).append('\n');
        text.append(labels.duration).append(": ").append(duration).append('\n');
        text.append(labels.manifest).append(": ").append(manifest).append('\n');
        text.append(labels.gaps).append(": ").append(gaps == null || gaps.isEmpty() ? labels.none : gaps).append('\n');
        if (check != null && !check.isEmpty()) {
            text.append(check).append('\n');
        }
        text.append('\n');
        text.append(labels.columns).append('\n');
        for (Row row : rows) {
            text.append(String.format(Locale.ROOT, "%4d  %-22s %10d  %s  %s", row.seq, row.name, row.size,
                    row.sha256, row.timestamp != null ? row.timestamp : labels.noTimestamp));
            if (row.note != null) {
                text.append("  (").append(row.note).append(')');
            }
            text.append('\n');
        }
        text.append('\n').append(labels.footer).append('\n');
        return text.toString();
    }

    private static void deflated(ZipOutputStream out, String path, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.DEFLATED);
        out.putNextEntry(entry);
        out.write(content);
        out.closeEntry();
    }

    /** A stored entry needs its size and CRC up front: one pass to compute them, one to copy. */
    private static void stored(ZipOutputStream out, String path, File file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
        }
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(file.length());
        entry.setCompressedSize(file.length());
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        copy(file, out, buffer);
        out.closeEntry();
    }

    private static void copy(File file, OutputStream out, byte[] buffer) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static byte[] readAll(File file) throws IOException {
        return java.nio.file.Files.readAllBytes(file.toPath());
    }
}
