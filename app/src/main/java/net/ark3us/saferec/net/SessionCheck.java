package net.ark3us.saferec.net;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The checks of scripts/verify_session.py, run on the phone before a session is handed over:
 * the one moment a chunk missing on Drive may still be on this phone. Same rules, so the
 * verifier gives the same verdict on the package: keep the two in step.
 *
 * - The manifest's timestamp certifies the manifest, or a prefix of it (it only grows: later
 *   lines are noted, an edited one fails).
 * - Every chunk it lists is present with the listed size and SHA-256, a repaired chunk's
 *   original too, and their timestamps (.tsa, .cut.tsa) certify them.
 * - A timestamp is valid when it certifies the file's SHA-256, its signature checks out and
 *   its certificate chain reaches a trusted root.
 */
public final class SessionCheck {

    public enum Kind {
        // Problems: the session is not verified
        NO_MANIFEST(true),
        EMPTY_MANIFEST(true),
        NEVER_CLOSED(true),
        MISSING(true),
        ALTERED(true),
        TIMESTAMP_INVALID(true),
        MANIFEST_EDITED(true),
        // Notes: true, but not a failure
        MANIFEST_UNTIMESTAMPED(false),
        ADDED_AFTER_TIMESTAMP(false),
        CUT(false),
        NOT_IN_MANIFEST(false);

        public final boolean problem;

        Kind(boolean problem) {
            this.problem = problem;
        }
    }

    public static final class Finding {
        public final Kind kind;
        public final int seq; // the chunk concerned, or -1
        public final String file; // the file concerned, or null

        Finding(Kind kind, int seq, String file) {
            this.kind = kind;
            this.seq = seq;
            this.file = file;
        }

        @Override
        public String toString() {
            return kind + (seq >= 0 ? " " + seq : "") + (file != null ? " " + file : "");
        }
    }

    public final List<Finding> findings = new ArrayList<>();
    /** The time of the manifest's valid timestamp, or null. */
    public Date manifestTime;
    public int checkedFiles;
    public int checkedTimestamps;

    private final Map<String, File> files;
    private final Set<TrustAnchor> roots;
    private final Map<String, byte[]> hashes = new HashMap<>();

    private SessionCheck(Map<String, File> files, Set<TrustAnchor> roots) {
        this.files = files;
        this.roots = roots;
    }

    public boolean ok() {
        for (Finding finding : findings) {
            if (finding.kind.problem) {
                return false;
            }
        }
        return true;
    }

    public boolean has(Kind kind) {
        for (Finding finding : findings) {
            if (finding.kind == kind) {
                return true;
            }
        }
        return false;
    }

    public List<Finding> problems() {
        return select(true);
    }

    public List<Finding> notes() {
        return select(false);
    }

    private List<Finding> select(boolean problems) {
        List<Finding> selected = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.kind.problem == problems) {
                selected.add(finding);
            }
        }
        return selected;
    }

    /** The SHA-256 of a session file, computed once; null when it is not there or unreadable. */
    public byte[] sha256(String name) {
        if (!hashes.containsKey(name)) {
            File file = files.get(name);
            hashes.put(name, file != null ? TsaClient.sha256(file) : null);
        }
        return hashes.get(name);
    }

    /**
     * Checks one session. files: every file of one session folder (video or audio), by its
     * name on Drive. roots: the certificates a timestamp's chain must reach.
     */
    public static SessionCheck run(String sessionId, Map<String, File> files, Set<TrustAnchor> roots) {
        SessionCheck check = new SessionCheck(files, roots);
        String manifestName = sessionId + ".0." + SessionManifest.EXTENSION;
        File manifest = files.get(manifestName);
        if (manifest == null) {
            check.add(Kind.NO_MANIFEST, -1, manifestName);
            return check;
        }
        byte[] content;
        try {
            content = Files.readAllBytes(manifest.toPath());
        } catch (IOException e) {
            check.add(Kind.NO_MANIFEST, -1, manifestName);
            return check;
        }
        Map<Integer, SessionManifest.Entry> entries = SessionManifest.entries(new String(content, StandardCharsets.UTF_8));
        if (entries.isEmpty()) {
            check.add(Kind.EMPTY_MANIFEST, -1, manifestName);
            return check;
        }
        check.manifestTimestamp(manifestName, content, entries.values());
        Set<String> listed = new HashSet<>();
        for (SessionManifest.Entry entry : entries.values()) {
            listed.add(entry.name);
            if (entry.state.equals(SessionManifest.STARTED)) {
                check.add(Kind.NEVER_CLOSED, entry.seq, entry.name);
                continue;
            }
            if (check.file(entry.seq, entry.name, entry.size, entry.sha256)) {
                check.timestamp(entry.seq, entry.name);
            }
            if (entry.state.equals(SessionManifest.REPAIRED)) {
                listed.add(entry.original);
                if (check.file(entry.seq, entry.original, entry.originalSize, entry.originalSha256)) {
                    check.timestamp(entry.seq, entry.original);
                }
            }
            if (entry.state.equals(SessionManifest.CUT)) {
                check.add(Kind.CUT, entry.seq, entry.name);
            }
        }
        for (String name : new TreeSet<>(files.keySet())) {
            if (name.endsWith(".mp4") && !listed.contains(name)) {
                check.add(Kind.NOT_IN_MANIFEST, -1, name);
            }
        }
        return check;
    }

    private void add(Kind kind, int seq, String file) {
        findings.add(new Finding(kind, seq, file));
    }

    private boolean file(int seq, String name, long size, String sha256) {
        File file = files.get(name);
        if (file == null) {
            add(Kind.MISSING, seq, name);
            return false;
        }
        byte[] actual = sha256(name); // unreadable is never "as listed", even against "sha256=unknown"
        if (actual == null || file.length() != size || !SessionManifest.hex(actual).equals(sha256)) {
            add(Kind.ALTERED, seq, name);
            return false;
        }
        checkedFiles++;
        return true;
    }

    private void timestamp(int seq, String name) {
        String base = name.substring(0, name.lastIndexOf('.'));
        String tokenName = base + (name.endsWith(".cut") ? ".cut.tsa" : ".tsa");
        File token = files.get(tokenName);
        if (token == null) {
            return; // timestamping was off, or the request failed: not a failure
        }
        if (validTime(read(token), sha256(name)) != null) {
            checkedTimestamps++;
        } else {
            add(Kind.TIMESTAMP_INVALID, seq, tokenName);
        }
    }

    /** The manifest only grows: its timestamp certifies the whole file or a prefix of it. */
    private void manifestTimestamp(String manifestName, byte[] content, Collection<SessionManifest.Entry> entries) {
        String tokenName = manifestName + ".tsa";
        File tokenFile = files.get(tokenName);
        if (tokenFile == null) {
            add(Kind.MANIFEST_UNTIMESTAMPED, -1, manifestName);
            return;
        }
        TimeStampToken token = read(tokenFile);
        if (token == null) {
            add(Kind.TIMESTAMP_INVALID, -1, tokenName);
            return;
        }
        byte[] imprint = token.getTimeStampInfo().getMessageImprintDigest();
        List<Integer> lineEnds = lineEnds(content);
        for (int count = lineEnds.size(); count > 0; count--) {
            byte[] digest = sha256(Arrays.copyOf(content, lineEnds.get(count - 1)));
            if (!Arrays.equals(digest, imprint)) {
                continue;
            }
            manifestTime = validTime(token, digest);
            if (manifestTime == null) {
                add(Kind.TIMESTAMP_INVALID, -1, tokenName);
                return;
            }
            checkedTimestamps++;
            for (SessionManifest.Entry entry : entries) {
                if (entry.line > count) {
                    add(Kind.ADDED_AFTER_TIMESTAMP, entry.seq, entry.name);
                }
            }
            return;
        }
        add(Kind.MANIFEST_EDITED, -1, manifestName);
    }

    /** Where each line ends (after its newline), as Python's splitlines(keepends=True) on "\n". */
    static List<Integer> lineEnds(byte[] content) {
        List<Integer> ends = new ArrayList<>();
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\n') {
                ends.add(i + 1);
            }
        }
        if (content.length > 0 && content[content.length - 1] != '\n') {
            ends.add(content.length);
        }
        return ends;
    }

    private static TimeStampToken read(File token) {
        try {
            return new TimeStampResponse(Files.readAllBytes(token.toPath())).getTimeStampToken();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The time a token certifies this SHA-256 digest at, or null when it does not: another
     * digest, a bad signature (BouncyCastle also wants a timestamping certificate, valid at
     * that time), or a certificate chain that reaches no trusted root.
     */
    private Date validTime(TimeStampToken token, byte[] digest) {
        if (token == null || digest == null) {
            return null;
        }
        try {
            if (!token.getTimeStampInfo().getMessageImprintAlgOID().equals(NISTObjectIdentifiers.id_sha256)
                    || !Arrays.equals(token.getTimeStampInfo().getMessageImprintDigest(), digest)) {
                return null;
            }
            Collection<X509CertificateHolder> signers = token.getCertificates().getMatches(token.getSID());
            if (signers.isEmpty()) {
                return null;
            }
            X509CertificateHolder signer = signers.iterator().next();
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(signer));

            // The chain, from the certificates the token carries up to a trusted root
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            List<X509Certificate> carried = new ArrayList<>();
            for (X509CertificateHolder holder : token.getCertificates().getMatches(null)) {
                carried.add(converter.getCertificate(holder));
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(converter.getCertificate(signer));
            PKIXBuilderParameters params = new PKIXBuilderParameters(roots, target);
            params.setRevocationEnabled(false); // as openssl ts -verify
            params.addCertPathChecker(new TimestampingUsage());
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(carried)));
            CertPathBuilder.getInstance("PKIX").build(params);
            return token.getTimeStampInfo().getGenTime();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The timestamping usage (a critical extension) is checked by BouncyCastle on the signer.
     * A PKIX implementation that does not know it would reject every chain.
     */
    private static final class TimestampingUsage extends PKIXCertPathChecker {
        private static final String EXTENDED_KEY_USAGE = Extension.extendedKeyUsage.getId();

        @Override
        public void init(boolean forward) {
        }

        @Override
        public boolean isForwardCheckingSupported() {
            return true;
        }

        @Override
        public Set<String> getSupportedExtensions() {
            return Collections.singleton(EXTENDED_KEY_USAGE);
        }

        @Override
        public void check(Certificate certificate, Collection<String> unresolvedCritExts) {
            unresolvedCritExts.remove(EXTENDED_KEY_USAGE);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The roots Android ships, as the verifier trusts Mozilla's list (both hold USERTrust RSA,
     * where Sectigo's timestamping chain goes up). Not the ones a user added: those could vouch
     * for anything.
     */
    public static Set<TrustAnchor> systemRoots() {
        Set<TrustAnchor> roots = new HashSet<>();
        try {
            KeyStore store = KeyStore.getInstance("AndroidCAStore");
            store.load(null, null);
            for (Enumeration<String> aliases = store.aliases(); aliases.hasMoreElements(); ) {
                String alias = aliases.nextElement();
                Certificate certificate = store.getCertificate(alias);
                if (alias.startsWith("system:") && certificate instanceof X509Certificate) {
                    roots.add(new TrustAnchor((X509Certificate) certificate, null));
                }
            }
        } catch (Exception e) {
            // No roots: every timestamp shows as invalid, never as valid
        }
        return roots;
    }
}
