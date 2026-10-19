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
    private static final WorkDir instance = new WorkDir();

    private final Object lock = new Object();
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

    /**
     * Moves the chunks left by a dead process to the outbox and deletes empty ones. A chunk cut
     * by a crash or a power cut has no MP4 index, but it is still evidence (repair tools can
     * read it). Returns how many were moved, or -1 when a recording uses the directory.
     */
    public int recover(File workDir, File outbox) {
        synchronized (lock) {
            if (users > 0) {
                return -1;
            }
            File[] orphans = workDir.listFiles(File::isFile);
            if (orphans == null || orphans.length == 0) {
                return 0;
            }
            if (!outbox.isDirectory() && !outbox.mkdirs()) {
                Log.e(TAG, "Cannot create outbox: " + outbox.getAbsolutePath());
                return 0;
            }
            int moved = 0;
            for (File orphan : orphans) {
                if (orphan.length() == 0) {
                    orphan.delete();
                } else if (orphan.renameTo(new File(outbox, orphan.getName()))) {
                    Log.w(TAG, "Recovered interrupted chunk: " + orphan.getName());
                    moved++;
                } else {
                    Log.e(TAG, "Failed to recover interrupted chunk: " + orphan.getName());
                }
            }
            return moved;
        }
    }
}
