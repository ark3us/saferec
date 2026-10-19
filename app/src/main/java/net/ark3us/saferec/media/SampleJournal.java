package net.ark3us.saferec.media;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A chunk's sample log, written next to it while it is recorded: the track formats, the
 * orientation and, for every sample, its track, size in the file, time and flags.
 *
 * MediaMuxer writes the MP4 index only when a chunk is closed, so a crash or a power cut
 * leaves a file whose samples are all there but cannot be found. With this log ChunkIndex
 * finds every sample exactly, without guessing. The log is deleted once the chunk is closed.
 */
public final class SampleJournal {
    public static final String EXTENSION = ".idx";
    public static final int VIDEO = 0;
    public static final int AUDIO = 1;
    private static final int MAGIC = 0x53524A31; // "SRJ1"
    private static final byte FORMAT = 'F';
    private static final byte ROTATION = 'R';
    private static final byte SAMPLE = 'S';
    private static final byte INT = 'i';
    private static final byte LONG = 'l';
    private static final byte STRING = 's';
    private static final byte BYTES = 'b';
    // Written in small batches: a crash loses at most the last ones, and MediaMuxer itself
    // keeps the last ~0.5 s in memory, so the file never holds more than the log anyway
    private static final int FLUSH_EVERY = 16;

    public static final class Sample {
        public final int track;
        public final int size;
        public final long ptsUs;
        public final int flags;

        public Sample(int track, int size, long ptsUs, int flags) {
            this.track = track;
            this.size = size;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    /** Format fields per track: Integer, Long, String or byte[] values. */
    public final Map<Integer, Map<String, Object>> formats = new LinkedHashMap<>();
    public int rotation = -1;
    public final List<Sample> samples = new ArrayList<>();

    private SampleJournal() {
    }

    /** The log of this chunk: same name plus ".idx". */
    public static File of(File chunk) {
        return new File(chunk.getPath() + EXTENSION);
    }

    public static boolean isJournal(File file) {
        return file.getName().endsWith(EXTENSION);
    }

    public static final class Writer implements Closeable {
        private final DataOutputStream out;
        private int unflushed;

        public Writer(File file) throws IOException {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
            out.writeInt(MAGIC);
        }

        public void format(int track, Map<String, Object> fields) throws IOException {
            out.writeByte(FORMAT);
            out.writeByte(track);
            out.writeInt(fields.size());
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                out.writeUTF(field.getKey());
                Object value = field.getValue();
                if (value instanceof Integer) {
                    out.writeByte(INT);
                    out.writeInt((Integer) value);
                } else if (value instanceof Long) {
                    out.writeByte(LONG);
                    out.writeLong((Long) value);
                } else if (value instanceof byte[]) {
                    byte[] bytes = (byte[]) value;
                    out.writeByte(BYTES);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                } else {
                    out.writeByte(STRING);
                    out.writeUTF(String.valueOf(value));
                }
            }
            out.flush();
        }

        public void rotation(int degrees) throws IOException {
            out.writeByte(ROTATION);
            out.writeInt(degrees);
            out.flush();
        }

        public void sample(int track, int size, long ptsUs, int flags) throws IOException {
            out.writeByte(SAMPLE);
            out.writeByte(track);
            out.writeInt(size);
            out.writeLong(ptsUs);
            out.writeInt(flags);
            if (++unflushed >= FLUSH_EVERY) {
                out.flush();
                unflushed = 0;
            }
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * Reads a log back. A record cut short by a crash or a power cut ends it: everything
     * before is kept. Returns null when the file is not a sample log.
     */
    public static SampleJournal read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                return null;
            }
            SampleJournal journal = new SampleJournal();
            try {
                while (true) {
                    byte type = in.readByte();
                    if (type == SAMPLE) {
                        int track = in.readByte();
                        int size = in.readInt();
                        long ptsUs = in.readLong();
                        int flags = in.readInt();
                        journal.samples.add(new Sample(track, size, ptsUs, flags));
                    } else if (type == FORMAT) {
                        int track = in.readByte();
                        journal.formats.put(track, readFields(in));
                    } else if (type == ROTATION) {
                        journal.rotation = in.readInt();
                    } else {
                        break; // garbage after a cut
                    }
                }
            } catch (EOFException ignored) {
                // the end of the log, or a record cut short
            }
            return journal;
        } catch (EOFException e) {
            return null; // shorter than its header
        }
    }

    private static Map<String, Object> readFields(DataInputStream in) throws IOException {
        int count = in.readInt();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            byte kind = in.readByte();
            switch (kind) {
                case INT:
                    fields.put(key, in.readInt());
                    break;
                case LONG:
                    fields.put(key, in.readLong());
                    break;
                case BYTES:
                    byte[] bytes = new byte[in.readInt()];
                    in.readFully(bytes);
                    fields.put(key, bytes);
                    break;
                default:
                    fields.put(key, in.readUTF());
            }
        }
        return fields;
    }
}
