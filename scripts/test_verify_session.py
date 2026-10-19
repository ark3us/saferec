"""Tests for verify_session.py, offline: a test TSA made with openssl signs the timestamps.

    uv run --with pytest --with certifi pytest scripts/test_verify_session.py -q --basetemp=tmp/pytest
"""

import hashlib
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import verify_session  # noqa: E402

SESSION = "1792394615054"
TS_CONF = """
[ tsa_config ]
dir = {dir}
serial = $dir/serial
crypto_device = builtin
signer_cert = $dir/tsa.crt
certs = $dir/tsa.crt
signer_key = $dir/tsa.key
signer_digest = sha256
default_policy = 1.2.3.4.1
digests = sha256
accuracy = secs:1
ordering = no
tsa_name = yes
ess_cert_id_chain = no
ess_cert_id_alg = sha256
"""


def run(*args, cwd):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True)


@pytest.fixture(scope="session")
def tsa(tmp_path_factory):
    """A root CA and a timestamping certificate it signed."""
    d = tmp_path_factory.mktemp("tsa")
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.crt",
        "-subj", "/CN=Test Root", "-days", "30", "-addext", "basicConstraints=critical,CA:TRUE",
        "-addext", "keyUsage=critical,keyCertSign,cRLSign", cwd=d)
    run("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "tsa.key", "-out", "tsa.csr",
        "-subj", "/CN=Test TSA", cwd=d)
    (d / "ext.cnf").write_text("extendedKeyUsage=critical,timeStamping\nkeyUsage=critical,digitalSignature\n")
    run("openssl", "x509", "-req", "-in", "tsa.csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial",
        "-out", "tsa.crt", "-days", "30", "-extfile", "ext.cnf", cwd=d)
    (d / "serial").write_text("01\n")
    (d / "ts.cnf").write_text(TS_CONF.format(dir=d))
    return d


def stamp(tsa, data: bytes, token: Path):
    (tsa / "data.bin").write_bytes(data)
    run("openssl", "ts", "-query", "-data", "data.bin", "-sha256", "-cert", "-out", "q.tsq", cwd=tsa)
    run("openssl", "ts", "-reply", "-queryfile", "q.tsq", "-config", "ts.cnf", "-section", "tsa_config",
        "-out", str(token), cwd=tsa)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Session:
    """A session folder as Drive gives it, with the manifest the app writes."""

    def __init__(self, folder: Path, tsa: Path):
        self.folder = folder
        self.tsa = tsa
        self.lines = [f"# SafeRec session {SESSION}, video: started, chunk, repaired or cut, sequence, "
                      "Drive name, bytes, SHA-256"]

    def chunk(self, seq: int, data: bytes, timestamp=True) -> str:
        name = f"{1792394620000 + seq}.{seq}.mp4"
        (self.folder / name).write_bytes(data)
        self.lines += [f"started {seq} {name}", f"chunk {seq} {name} {len(data)} sha256={sha(data)}"]
        if timestamp:
            stamp(self.tsa, data, self.folder / name.replace(".mp4", ".tsa"))
        return name

    def repaired(self, seq: int, copy: bytes, original: bytes):
        base = f"{1792394620000 + seq}.{seq}"
        (self.folder / f"{base}.mp4").write_bytes(copy)
        (self.folder / f"{base}.cut").write_bytes(original)
        stamp(self.tsa, copy, self.folder / f"{base}.tsa")
        stamp(self.tsa, original, self.folder / f"{base}.cut.tsa")
        self.lines += [f"started {seq} {base}.mp4",
                       f"repaired {seq} {base}.mp4 {len(copy)} sha256={sha(copy)} original {base}.cut "
                       f"{len(original)} sha256={sha(original)}"]

    @property
    def manifest(self) -> Path:
        return self.folder / f"{SESSION}.0.manifest"

    def write(self, timestamp=True):
        text = "\n".join(self.lines) + "\n"
        self.manifest.write_text(text)
        if timestamp:
            stamp(self.tsa, text.encode(), self.folder / f"{SESSION}.0.manifest.tsa")


@pytest.fixture
def session(tmp_path, tsa):
    folder = tmp_path / SESSION / "video"
    folder.mkdir(parents=True)
    s = Session(folder, tsa)
    for seq in range(3):
        s.chunk(seq, f"chunk {seq} ".encode() * 1000)
    return s


def verify(session, capsys, ca=None):
    status = verify_session.main([str(session.folder.parent.parent), "--ca-file", str(ca or session.tsa / "ca.crt")])
    return status, capsys.readouterr().out


def test_a_complete_session_verifies(session, capsys):
    session.write()
    status, out = verify(session, capsys)
    assert status == 0, out
    assert "manifest: timestamp valid, unchanged since" in out
    assert out.count("timestamp valid") == 4  # the manifest and its 3 chunks
    assert "=> COMPLETE" in out


def test_a_missing_chunk_fails(session, capsys):
    session.write()
    (session.folder / "1792394620001.1.mp4").unlink()
    status, out = verify(session, capsys)
    assert status == 1
    assert "chunk 1: 1792394620001.1.mp4 is missing" in out


def test_an_altered_chunk_fails(session, capsys):
    session.write()
    path = session.folder / "1792394620002.2.mp4"
    path.write_bytes(path.read_bytes().replace(b"chunk", b"CHUNK", 1))
    status, out = verify(session, capsys)
    assert status == 1
    assert "1792394620002.2.mp4 was altered" in out


def test_a_chunk_started_and_never_closed_is_lost(session, capsys):
    session.lines.append("started 3 1792394620003.3.mp4")
    session.write()
    status, out = verify(session, capsys)
    assert status == 1
    assert "chunk 3 (1792394620003.3.mp4) was recorded but never closed nor recovered: lost" in out


def test_lines_added_after_the_timestamp_are_told_apart(session, capsys):
    session.write()  # timestamped with chunks 0-2
    token = (session.folder / f"{SESSION}.0.manifest.tsa").read_bytes()
    session.chunk(3, b"recovered later " * 100)
    session.write(timestamp=False)
    (session.folder / f"{SESSION}.0.manifest.tsa").write_bytes(token)
    status, out = verify(session, capsys)
    assert status == 0, out
    assert "timestamp valid for its first 7 lines" in out
    assert "2 manifest lines were added after its timestamp (chunks 3)" in out


def test_an_edited_manifest_fails(session, capsys):
    session.write()
    text = session.manifest.read_text()
    session.manifest.write_text(text.replace("chunk 1 ", "chunk 7 ", 1))  # renumbered after the timestamp
    status, out = verify(session, capsys)
    assert status == 1
    assert "the manifest was edited after its timestamp" in out


def test_a_repaired_chunk_and_its_original_verify(session, capsys):
    session.repaired(3, b"playable copy " * 100, b"cut original " * 100)
    session.write()
    status, out = verify(session, capsys)
    assert status == 0, out
    assert "repaired chunk 3: 1792394620003.3.mp4: timestamp valid" in out
    assert "original of chunk 3: 1792394620003.3.cut: timestamp valid" in out


def test_a_timestamp_of_another_file_fails(session, capsys):
    session.write()
    other = (session.folder / "1792394620001.1.tsa").read_bytes()
    (session.folder / "1792394620000.0.tsa").write_bytes(other)
    status, out = verify(session, capsys)
    assert status == 1
    assert "1792394620000.0.tsa does not certify 1792394620000.0.mp4" in out


def test_an_untrusted_timestamping_authority_fails(session, capsys):
    session.write()
    import certifi
    status, out = verify(session, capsys, ca=certifi.where())  # Mozilla's roots do not hold the test CA
    assert status == 1
    assert "the signature of" in out and "is invalid" in out


def test_a_chunk_without_timestamp_is_not_a_failure(tmp_path, tsa, capsys):
    folder = tmp_path / SESSION / "audio"
    folder.mkdir(parents=True)
    s = Session(folder, tsa)
    s.chunk(0, b"no timestamp " * 50, timestamp=False)
    s.write(timestamp=False)
    status, out = verify(s, capsys)
    assert status == 0, out
    assert "the manifest has no timestamp" in out
