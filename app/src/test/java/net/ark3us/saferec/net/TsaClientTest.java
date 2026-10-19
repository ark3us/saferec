package net.ark3us.saferec.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TsaClientTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    public void sha256MatchesTheStandardTestVector() throws Exception {
        File file = tmp.newFile("chunk.mp4");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        // FIPS 180-2 test vector for "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(TsaClient.sha256(file)));
    }

    @Test
    public void unreadableFileGivesNoHash() {
        assertNull(TsaClient.sha256(new File(tmp.getRoot(), "missing.mp4")));
    }
}
