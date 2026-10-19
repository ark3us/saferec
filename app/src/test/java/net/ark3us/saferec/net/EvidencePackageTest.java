package net.ark3us.saferec.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EvidencePackageTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SESSION = "1792429746374";

    private File file(String name, String content) throws Exception {
        File file = new File(tmp.getRoot(), "dl_" + name);
        Files.write(file.toPath(), content.getBytes());
        return file;
    }

    private Map<String, ZipEntry> entries(File zip) throws Exception {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        try (ZipFile zipFile = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> all = zipFile.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                entries.put(entry.getName(), entry);
            }
        }
        return entries;
    }

    private byte[] content(File zip, String name) throws Exception {
        try (ZipFile zipFile = new ZipFile(zip)) {
            return zipFile.getInputStream(zipFile.getEntry(name)).readAllBytes();
        }
    }

    @Test
    public void thePackageHoldsTheDriveLayoutTheVerifierExpects() throws Exception {
        List<EvidencePackage.Item> evidence = new ArrayList<>();
        for (String name : new String[]{"1792429747336.0.mp4", "1792429747336.0.tsa", "1792429758115.1.mp4",
                "1792429758115.1.cut", "1792429758115.1.cut.tsa", SESSION + ".0.manifest"}) {
            evidence.add(new EvidencePackage.Item(file(name, "content of " + name), name));
        }
        File merged = file("merged.mp4", "the whole video");
        File zip = new File(tmp.getRoot(), "package.zip");
        EvidencePackage.write(zip, SESSION, "video", "readme", "report", merged, evidence, "verifier".getBytes());

        Map<String, ZipEntry> entries = entries(zip);
        String root = "SafeRec-" + SESSION + "/";
        assertEquals(Arrays.asList(root + "README.txt", root + "report.txt", root + "merged.mp4",
                root + "evidence/" + SESSION + "/video/1792429747336.0.mp4",
                root + "evidence/" + SESSION + "/video/1792429747336.0.tsa",
                root + "evidence/" + SESSION + "/video/1792429758115.1.mp4",
                root + "evidence/" + SESSION + "/video/1792429758115.1.cut",
                root + "evidence/" + SESSION + "/video/1792429758115.1.cut.tsa",
                root + "evidence/" + SESSION + "/video/" + SESSION + ".0.manifest",
                root + "verify_session.py"), new ArrayList<>(entries.keySet()));
        // Videos are not compressed again; everything else is
        assertEquals(ZipEntry.STORED, entries.get(root + "merged.mp4").getMethod());
        assertEquals(ZipEntry.STORED, entries.get(root + "evidence/" + SESSION + "/video/1792429758115.1.cut").getMethod());
        assertEquals(ZipEntry.DEFLATED, entries.get(root + "evidence/" + SESSION + "/video/1792429747336.0.tsa").getMethod());
        // Byte for byte what came from Drive
        assertArrayEquals("content of 1792429758115.1.mp4".getBytes(),
                content(zip, root + "evidence/" + SESSION + "/video/1792429758115.1.mp4"));
        assertArrayEquals("verifier".getBytes(), content(zip, root + "verify_session.py"));
    }

    @Test
    public void aPackageWithoutMergedVideoStillHoldsTheEvidence() throws Exception {
        File zip = new File(tmp.getRoot(), "package.zip");
        EvidencePackage.write(zip, SESSION, "audio", "readme", "report", null,
                Collections.singletonList(new EvidencePackage.Item(file("a.0.mp4", "chunk"), "a.0.mp4")), new byte[0]);
        Map<String, ZipEntry> entries = entries(zip);
        assertNull(entries.get("SafeRec-" + SESSION + "/merged.mp4"));
        assertTrue(entries.containsKey("SafeRec-" + SESSION + "/evidence/" + SESSION + "/audio/a.0.mp4"));
    }

    @Test
    public void theReportListsEveryChunk() {
        EvidencePackage.Labels labels = new EvidencePackage.Labels();
        labels.title = "SafeRec session %s";
        labels.started = "Started";
        labels.type = "Type";
        labels.chunks = "Chunks";
        labels.duration = "Duration";
        labels.manifest = "Manifest";
        labels.gaps = "Gaps";
        labels.none = "none";
        labels.columns = "columns";
        labels.noTimestamp = "no timestamp";
        labels.footer = "footer";
        List<EvidencePackage.Row> rows = Arrays.asList(
                new EvidencePackage.Row(0, "1792429747336.0.mp4", 1140800, "da36fd", "2026-10-19 17:09:17 UTC", null),
                new EvidencePackage.Row(1, "1792429758115.1.mp4", 465349, "bbe152", null, "repaired"));
        String report = EvidencePackage.report(labels, SESSION, "video", "2026-10-19 19:09:06 CEST", "0:15",
                "present, timestamped", "", "Checked on this phone: NOT VERIFIED\n  - chunk 2 is missing", rows);
        assertTrue(report, report.startsWith("SafeRec session " + SESSION + "\n"));
        assertTrue(report, report.contains("Chunks: 2\n"));
        assertTrue(report, report.contains("Gaps: none\nChecked on this phone: NOT VERIFIED\n  - chunk 2 is missing\n\ncolumns\n"));
        assertTrue(report, report.contains("   0  1792429747336.0.mp4       1140800  da36fd  2026-10-19 17:09:17 UTC\n"));
        assertTrue(report, report.contains("   1  1792429758115.1.mp4        465349  bbe152  no timestamp  (repaired)\n"));
        assertTrue(report, report.endsWith("footer\n"));
    }

    @Test
    public void packagesOlderThanADayAreDeleted() throws Exception {
        long now = System.currentTimeMillis();
        File old = tmp.newFile("SafeRec-1.zip");
        File recent = tmp.newFile("SafeRec-2.zip");
        File other = tmp.newFile("merged_3.mp4");
        assertTrue(old.setLastModified(now - 25 * 3600_000L));
        assertTrue(other.setLastModified(now - 25 * 3600_000L));
        assertEquals(1, EvidencePackage.deleteOld(tmp.getRoot(), 24 * 3600_000L, now));
        assertTrue(!old.exists() && recent.exists() && other.exists()); // only old packages
    }
}
