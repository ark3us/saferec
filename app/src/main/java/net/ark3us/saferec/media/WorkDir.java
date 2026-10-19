package net.ark3us.saferec.media;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * The directory chunks are written in, before the service hashes them and moves them to the
 * upload outbox. A chunk left there by a dead process (crash, sudden power cut) must reach
 * the outbox too, but only while no recording uses the directory. Moving the chunk being
 * written would upload half a file, and moving a closed one before its post-processing would
 * skip its TSA hash.
 */
public final class WorkDir {
    private static final String TAG = WorkDir.class.getSimpleName();
    private static final String DIR = "recording";
    private static final String REPAIR_DIR = "repair";
    private static final String REPAIRING = ".repairing";
    /** The untouched original of a repaired chunk. */
    public static final String CUT_EXTENSION = "cut";
    private static final WorkDir instance = new WorkDir();

    private final Object lock = new Object();
    private final Object repairLock = new Object(); // one repair at a time, outside lock
    private int users; // guarded by lock: recordings whose chunks have not all left the directory

    WorkDir() {
    }

    /** One per process: the service and the outbox job must see the same users. */
    public static WorkDir get() {
        return instance;
    }

    public static File dir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    /** Chunks taken from the work dir, waiting for their repair. */
    public static File repairDir(Context context) {
        return new File(context.getFilesDir(), REPAIR_DIR);
    }

    /** A recording is about to write here. Waits while leftovers are being moved. */
    public void acquire() {
        synchronized (lock) {
            users++;
        }
    }

    /** That recording's last chunk has left the directory. */
    public void release() {
        synchronized (lock) {
            if (users > 0) {
                users--;
            }
        }
    }

    public boolean inUse() {
        synchronized (lock) {
            return users > 0;
        }
    }

    /** Rebuilds a playable chunk from a cut one and its sample log. */
    public interface Repairer {
        /** Writes the repaired chunk to out; false when it cannot (out is then deleted). */
        boolean repair(File cut, File journal, File out);
    }

    public int recover(File workDir, File outbox) {
        return recover(workDir, outbox, null);
    }

    /**
     * Moves the chunks left by a dead process to the outbox and deletes empty ones. A chunk cut
     * by a crash or a power cut has no MP4 index: with its sample log, the repairer rebuilds a
     * playable copy, which goes to the outbox under the chunk's name, and the cut file goes
     * along as ".cut", the untouched original. Without a log (or if the repair fails) the cut
     * file goes as it is: still evidence, readable with repair tools.
     *
     * Under the lock the chunks are only moved to "repair/" next to the work dir (renames,
     * fast): a recording start never waits for a repair. Repairs run after, one at a time,
     * and pick up what a dead process left in "repair/".
     *
     * Returns how many chunks reached the outbox, or -1 when a recording uses the directory.
     */
    public int recover(File workDir, File outbox, Repairer repairer) {
        File repairDir = new File(workDir.getParentFile(), REPAIR_DIR);
        synchronized (lock) {
            if (users > 0) {
                return -1;
            }
            File[] orphans = workDir.listFiles(File::isFile);
            if (orphans != null && orphans.length > 0) {
                if (!repairDir.isDirectory() && !repairDir.mkdirs()) {
                    Log.e(TAG, "Cannot create " + repairDir.getAbsolutePath());
                    return 0;
                }
                for (File orphan : orphans) {
                    if (SampleJournal.isJournal(orphan)) {
                        continue; // moves with its chunk
                    }
                    File journal = SampleJournal.of(orphan);
                    if (orphan.length() == 0) {
                        orphan.delete();
                        journal.delete();
                    } else if (orphan.renameTo(new File(repairDir, orphan.getName()))) {
                        if (journal.exists() && !journal.renameTo(SampleJournal.of(new File(repairDir, orphan.getName())))) {
                            journal.delete();
                        }
                    } else {
                        Log.e(TAG, "Failed to recover interrupted chunk: " + orphan.getName());
                    }
                }
                // Logs whose chunk was closed, or never written
                File[] logs = workDir.listFiles(SampleJournal::isJournal);
                if (logs != null) {
                    for (File log : logs) {
                        if (!new File(log.getPath().substring(0, log.getPath().length() - SampleJournal.EXTENSION.length())).exists()) {
                            log.delete();
                        }
                    }
                }
            }
        }
        synchronized (repairLock) {
            return finishRecovery(repairDir, outbox, repairer);
        }
    }

    private static int finishRecovery(File repairDir, File outbox, Repairer repairer) {
        File[] chunks = repairDir.listFiles(f -> f.isFile() && !SampleJournal.isJournal(f) && !f.getName().endsWith(REPAIRING));
        File[] leftovers = repairDir.listFiles(f -> f.getName().endsWith(REPAIRING));
        if (leftovers != null) {
            for (File leftover : leftovers) {
                leftover.delete(); // a repair cut short: it starts over
            }
        }
        if (chunks == null || chunks.length == 0) {
            return 0;
        }
        if (!outbox.isDirectory() && !outbox.mkdirs()) {
            Log.e(TAG, "Cannot create outbox: " + outbox.getAbsolutePath());
            return 0;
        }
        int moved = 0;
        for (File chunk : chunks) {
            File journal = SampleJournal.of(chunk);
            File repaired = new File(repairDir, chunk.getName() + REPAIRING);
            // The playable copy first: if the process dies before the original follows, the
            // next recovery repairs again. Once the copy is there, the original goes as .cut
            // only, so it can never replace the copy.
            boolean copyInOutbox = repairer != null && journal.exists()
                    && repairer.repair(chunk, journal, repaired)
                    && repaired.renameTo(new File(outbox, chunk.getName()));
            repaired.delete();
            String target = copyInOutbox ? cutName(chunk.getName()) : chunk.getName();
            if (chunk.renameTo(new File(outbox, target))) {
                Log.w(TAG, copyInOutbox
                        ? "Repaired interrupted chunk: " + chunk.getName() + " (original kept as " + target + ")"
                        : "Recovered interrupted chunk: " + chunk.getName());
                journal.delete();
                moved++;
            } else {
                Log.e(TAG, "Failed to recover interrupted chunk: " + chunk.getName() + ", retrying at the next recovery");
            }
        }
        return moved;
    }

    /** "x_video_1.0.mp4" → "x_video_1.0.cut". */
    static String cutName(String chunkName) {
        int dot = chunkName.lastIndexOf('.');
        return (dot > 0 ? chunkName.substring(0, dot) : chunkName) + "." + CUT_EXTENSION;
    }
}
