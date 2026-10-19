package net.ark3us.saferec.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The rules of scripts/verify_session.py, on sessions signed by a test authority. */
public class SessionCheckTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SESSION = "1792394615054";
    private static Authority authority;
    private static Authority stranger;

    @BeforeClass
    public static void authorities() throws Exception {
        authority = new Authority("Test");
        stranger = new Authority("Stranger");
    }

    /** A root and a timestamping certificate it signed, like Sectigo's chain in miniature. */
    private static final class Authority {
        final X509Certificate root;
        final X509Certificate signer;
        final PrivateKey signerKey;
        private long serial = 1;

        Authority(String name) throws Exception {
            KeyPair rootKeys = rsa();
            KeyPair signerKeys = rsa();
            Date from = new Date(System.currentTimeMillis() - 86_400_000L);
            Date to = new Date(System.currentTimeMillis() + 30 * 86_400_000L);
            X500Name rootName = new X500Name("CN=" + name + " Root");
            root = certificate(new JcaX509v3CertificateBuilder(rootName, BigInteger.ONE, from, to, rootName, rootKeys.getPublic())
                    .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                    .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)), rootKeys);
            signer = certificate(new JcaX509v3CertificateBuilder(root, BigInteger.valueOf(2), from, to,
                    new X500Name("CN=" + name + " TSA"), signerKeys.getPublic())
                    .addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))
                    .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature)), rootKeys);
            signerKey = signerKeys.getPrivate();
        }

        private static KeyPair rsa() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }

        private static X509Certificate certificate(X509v3CertificateBuilder builder, KeyPair issuer) throws Exception {
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuer.getPrivate())));
        }

        byte[] stamp(byte[] data) throws Exception {
            TimeStampTokenGenerator tokens = new TimeStampTokenGenerator(
                    new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", signerKey, signer),
                    new JcaDigestCalculatorProviderBuilder().build().get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
                    new ASN1ObjectIdentifier("1.2.3.4.1"));
            tokens.addCertificates(new JcaCertStore(Arrays.asList(signer, root)));
            TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
            requests.setCertReq(true);
            TimeStampRequest request = requests.generate(TSPAlgorithms.SHA256, sha(data));
            return new TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED)
                    .generate(request, BigInteger.valueOf(serial++), new Date()).getEncoded();
        }

        Set<TrustAnchor> roots() {
            return Collections.singleton(new TrustAnchor(root, null));
        }
    }

    private static byte[] sha(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    /** A session folder as Drive gives it, with the manifest the app writes. */
    private final class Session {
        final Map<String, File> files = new HashMap<>();
        final List<String> lines = new ArrayList<>(Collections.singletonList(
                "# SafeRec session " + SESSION + ", video: started, chunk, repaired or cut, sequence, Drive name, bytes, SHA-256"));

        void put(String name, byte[] content) throws Exception {
            File file = new File(tmp.getRoot(), name);
            Files.write(file.toPath(), content);
            files.put(name, file);
        }

        String chunk(int seq, boolean timestamp) throws Exception {
            String name = (1792394620000L + seq) + "." + seq + ".mp4";
            byte[] data = data(seq);
            put(name, data);
            lines.add("started " + seq + " " + name);
            lines.add("chunk " + seq + " " + name + " " + data.length + " sha256=" + SessionManifest.hex(sha(data)));
            if (timestamp) {
                put(name.replace(".mp4", ".tsa"), authority.stamp(data));
            }
            return name;
        }

        void repaired(int seq) throws Exception {
            String base = (1792394620000L + seq) + "." + seq;
            byte[] copy = data(seq);
            byte[] original = ("cut " + seq).getBytes(StandardCharsets.UTF_8);
            put(base + ".mp4", copy);
            put(base + ".cut", original);
            put(base + ".tsa", authority.stamp(copy));
            put(base + ".cut.tsa", authority.stamp(original));
            lines.add("started " + seq + " " + base + ".mp4");
            lines.add("repaired " + seq + " " + base + ".mp4 " + copy.length + " sha256=" + SessionManifest.hex(sha(copy))
                    + " original " + base + ".cut " + original.length + " sha256=" + SessionManifest.hex(sha(original)));
        }

        void write(boolean timestamp) throws Exception {
            byte[] text = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
            put(SESSION + ".0.manifest", text);
            if (timestamp) {
                put(SESSION + ".0.manifest.tsa", authority.stamp(text));
            }
        }

        SessionCheck check() {
            return check(authority.roots());
        }

        SessionCheck check(Set<TrustAnchor> roots) {
            return SessionCheck.run(SESSION, files, roots);
        }
    }

    private static byte[] data(int seq) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            text.append("chunk ").append(seq).append(' ');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Session threeChunks() throws Exception {
        Session session = new Session();
        for (int seq = 0; seq < 3; seq++) {
            session.chunk(seq, true);
        }
        return session;
    }

    private static String kinds(List<SessionCheck.Finding> findings) {
        return findings.toString();
    }

    @Test
    public void aCompleteSessionVerifies() throws Exception {
        Session session = threeChunks();
        session.write(true);
        SessionCheck check = session.check();
        assertTrue(kinds(check.findings), check.findings.isEmpty());
        assertTrue(check.ok());
        assertEquals(3, check.checkedFiles);
        assertEquals(4, check.checkedTimestamps); // 3 chunks and the manifest
        assertNotNull(check.manifestTime);
    }

    @Test
    public void aMissingChunkFails() throws Exception {
        Session session = threeChunks();
        session.write(true);
        session.files.remove("1792394620001.1.mp4");
        SessionCheck check = session.check();
        assertFalse(check.ok());
        assertEquals("[MISSING 1 1792394620001.1.mp4]", kinds(check.problems()));
    }

    @Test
    public void anAlteredChunkFails() throws Exception {
        Session session = threeChunks();
        session.write(true);
        File chunk = session.files.get("1792394620001.1.mp4");
        byte[] content = Files.readAllBytes(chunk.toPath());
        content[100] ^= 1; // same size, one bit changed
        Files.write(chunk.toPath(), content);
        assertEquals("[ALTERED 1 1792394620001.1.mp4]", kinds(session.check().problems()));
    }

    @Test
    public void aChunkStartedAndNeverClosedIsLost() throws Exception {
        Session session = threeChunks();
        session.lines.add("started 3 1792394620003.3.mp4");
        session.write(true);
        assertEquals("[NEVER_CLOSED 3 1792394620003.3.mp4]", kinds(session.check().problems()));
    }

    @Test
    public void linesAddedAfterTheTimestampAreToldApart() throws Exception {
        Session session = threeChunks();
        session.write(true);
        byte[] token = Files.readAllBytes(session.files.get(SESSION + ".0.manifest.tsa").toPath());
        session.repaired(3); // a recovery after the stop
        session.write(false);
        session.put(SESSION + ".0.manifest.tsa", token);
        SessionCheck check = session.check();
        assertTrue(kinds(check.problems()), check.ok());
        assertEquals("[ADDED_AFTER_TIMESTAMP 3 1792394620003.3.mp4]", kinds(check.notes()));
        assertNotNull(check.manifestTime);
    }

    @Test
    public void anEditedManifestFails() throws Exception {
        Session session = threeChunks();
        session.write(true);
        File manifest = session.files.get(SESSION + ".0.manifest");
        String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        Files.write(manifest.toPath(), text.replace("started 1 ", "started  1 ").getBytes(StandardCharsets.UTF_8));
        assertEquals("[MANIFEST_EDITED " + SESSION + ".0.manifest]", kinds(session.check().problems()));
    }

    @Test
    public void aRepairedChunkAndItsOriginalVerify() throws Exception {
        Session session = threeChunks();
        session.repaired(3);
        session.write(true);
        SessionCheck check = session.check();
        assertTrue(kinds(check.findings), check.findings.isEmpty());
        assertEquals(5, check.checkedFiles);
        assertEquals(6, check.checkedTimestamps);

        session.files.remove("1792394620003.3.cut");
        assertEquals("[MISSING 3 1792394620003.3.cut]", kinds(session.check().problems()));
    }

    @Test
    public void aTimestampOfAnotherFileFails() throws Exception {
        Session session = threeChunks();
        session.write(true);
        session.put("1792394620001.1.tsa", Files.readAllBytes(session.files.get("1792394620000.0.tsa").toPath()));
        assertEquals("[TIMESTAMP_INVALID 1 1792394620001.1.tsa]", kinds(session.check().problems()));
    }

    @Test
    public void aTamperedSignatureFails() throws Exception {
        Session session = threeChunks();
        session.write(true);
        File token = session.files.get("1792394620001.1.tsa");
        byte[] content = Files.readAllBytes(token.toPath());
        content[content.length - 1] ^= 1; // the signature value ends the reply
        Files.write(token.toPath(), content);
        assertEquals("[TIMESTAMP_INVALID 1 1792394620001.1.tsa]", kinds(session.check().problems()));
    }

    @Test
    public void anUntrustedTimestampingAuthorityFails() throws Exception {
        Session session = threeChunks();
        session.write(true);
        SessionCheck check = session.check(stranger.roots());
        assertEquals(4, check.problems().size()); // every timestamp, the manifest's too
        for (SessionCheck.Finding finding : check.problems()) {
            assertEquals(SessionCheck.Kind.TIMESTAMP_INVALID, finding.kind);
        }
    }

    @Test
    public void withoutTimestampsNothingFails() throws Exception {
        Session session = new Session();
        session.chunk(0, false);
        session.write(false);
        SessionCheck check = session.check();
        assertTrue(check.ok());
        assertEquals("[MANIFEST_UNTIMESTAMPED " + SESSION + ".0.manifest]", kinds(check.notes()));
        assertEquals(0, check.checkedTimestamps);
    }

    @Test
    public void aChunkOutsideTheManifestIsNotedAndNoManifestFails() throws Exception {
        Session session = threeChunks();
        session.write(true);
        session.put("1792394620009.9.mp4", data(9));
        SessionCheck check = session.check();
        assertTrue(check.ok());
        assertEquals("[NOT_IN_MANIFEST 1792394620009.9.mp4]", kinds(check.notes()));

        session.files.remove(SESSION + ".0.manifest");
        assertEquals("[NO_MANIFEST " + SESSION + ".0.manifest]", kinds(session.check().problems()));
    }

    @Test
    public void linesEndAsPythonSplitsThem() {
        assertEquals(Arrays.asList(2, 4), SessionCheck.lineEnds("a\nb\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Arrays.asList(2, 3), SessionCheck.lineEnds("a\nb".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Collections.emptyList(), SessionCheck.lineEnds(new byte[0]));
    }
}
