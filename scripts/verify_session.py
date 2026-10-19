#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["certifi"]
# ///
"""Verifies SafeRec sessions downloaded from Google Drive.

Download a session folder from Drive (right click > Download), unzip it, then run:

    uv run scripts/verify_session.py <folder>

For every session manifest found under the folder it checks:
  - the manifest's RFC 3161 timestamp: signature, certificate chain and that it certifies this
    manifest. The manifest only grows, so lines added after the timestamp (a later recovery)
    are told apart from an edit;
  - every chunk the manifest lists: present, with the listed size and SHA-256;
  - every chunk's own timestamp (.tsa) and a repaired chunk's original (.cut, .cut.tsa).

Needs the openssl command. The root certificates come from certifi (Mozilla's list), which
holds USERTrust RSA, the root Sectigo's timestamping chain goes up to. Exit status: 0 when
every session is complete and unchanged, 1 when one is not, 2 on a usage error.
"""

import argparse
import hashlib
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

CLOSED_STATES = ("chunk", "repaired", "cut")


@dataclass
class Entry:
    """The last state of one chunk in the manifest."""
    seq: int
    state: str
    name: str
    size: Optional[int] = None
    sha256: Optional[str] = None
    original: Optional[str] = None
    original_size: Optional[int] = None
    original_sha256: Optional[str] = None
    line: int = 0  # 1-based line of the manifest that gave this state


@dataclass
class Report:
    session: str
    folder: Path
    problems: List[str] = field(default_factory=list)  # missing or altered: the verdict
    notes: List[str] = field(default_factory=list)  # true, but not a failure
    checked: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_manifest(text: str) -> Dict[int, Entry]:
    entries: Dict[int, Entry] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        fields = line.split()
        if not fields or line.startswith("#") or fields[0] not in ("started",) + CLOSED_STATES:
            continue
        try:
            seq = int(fields[1])
            entry = Entry(seq=seq, state=fields[0], name=fields[2], line=number)
            if fields[0] in CLOSED_STATES:
                entry.size = int(fields[3])
                entry.sha256 = fields[4].split("=", 1)[1]
            if fields[0] == "repaired":
                entry.original = fields[6]
                entry.original_size = int(fields[7])
                entry.original_sha256 = fields[8].split("=", 1)[1]
        except (IndexError, ValueError):
            continue
        # A late "started" (a recovery done twice) never undoes how the chunk ended
        if entry.state != "started" or seq not in entries:
            entries[seq] = entry
    return entries


class Timestamps:
    """RFC 3161 checks with the openssl command."""

    def __init__(self, ca_file: str):
        self.ca_file = ca_file

    def info(self, token: Path) -> Optional[dict]:
        """The time and message imprint (hex) of a timestamp reply, or None if unreadable."""
        result = subprocess.run(["openssl", "ts", "-reply", "-in", str(token), "-text"],
                                capture_output=True, text=True)
        if result.returncode != 0 or "Granted" not in result.stdout:
            return None
        time = re.search(r"Time stamp: (.+)", result.stdout)
        block = re.search(r"Message data:\n(.*?)\n(?:Serial number|Time stamp)", result.stdout, re.S)
        if not time or not block:
            return None
        # "    0000 - 4f b5 ed 24 db d8 e0 eb-d6 ec 18 79 54 af 44 4a   O..$..." : the hex part has
        # a fixed width; the ASCII column after it may hold letters that look like hex
        imprint = ""
        for line in block.group(1).splitlines():
            match = re.match(r"\s*[0-9a-f]{4} - (.{47})", line + " " * 47)
            if match:
                imprint += re.sub(r"[ -]", "", match.group(1))
        return {"time": time.group(1).strip(), "imprint": imprint[:64]}

    def verify(self, token: Path, digest_hex: str) -> bool:
        """Signature and chain up to a trusted root, and that the token certifies this digest."""
        result = subprocess.run(["openssl", "ts", "-verify", "-digest", digest_hex, "-in", str(token),
                                 "-CAfile", self.ca_file], capture_output=True, text=True)
        return result.returncode == 0 and "Verification: OK" in result.stdout


def check_file(report: Report, folder: Path, name: str, size: int, sha256: str, what: str) -> bool:
    path = folder / name
    if not path.is_file():
        report.problems.append(f"{what} {name} is missing")
        return False
    actual = sha256_of(path)
    if path.stat().st_size != size or actual != sha256:
        report.problems.append(f"{what} {name} was altered: SHA-256 {actual[:16]}… instead of {sha256[:16]}…")
        return False
    report.checked.append(f"{what} {name}: present, size and SHA-256 as listed")
    return True


def check_token(report: Report, stamps: Timestamps, file: Path, what: str) -> None:
    token = file.parent / (file.name.rsplit(".", 1)[0] + (".cut.tsa" if file.name.endswith(".cut") else ".tsa"))
    if not token.is_file():
        return  # timestamping was off, or the request failed: not a failure
    info = stamps.info(token)
    if info and stamps.verify(token, sha256_of(file)):
        report.checked.append(f"{what} {file.name}: timestamp valid, {info['time']}")
    else:
        report.problems.append(f"the timestamp {token.name} does not certify {file.name}, or its signature is invalid")


def check_manifest_token(report: Report, stamps: Timestamps, manifest: Path, entries: Dict[int, Entry]) -> None:
    token = manifest.with_name(manifest.name + ".tsa")
    if not token.is_file():
        report.notes.append("the manifest has no timestamp: anyone with access to the folder could have edited it")
        return
    info = stamps.info(token)
    if not info:
        report.problems.append(f"{token.name} is not a readable timestamp reply")
        return
    content = manifest.read_bytes()
    lines = content.splitlines(keepends=True)
    # The manifest only grows: the timestamped version is the whole file or a prefix of it
    for count in range(len(lines), 0, -1):
        prefix = b"".join(lines[:count])
        digest = hashlib.sha256(prefix).hexdigest()
        if digest != info["imprint"]:
            continue
        if not stamps.verify(token, digest):
            report.problems.append(f"the signature of {token.name} is invalid")
            return
        if count == len(lines):
            report.checked.append(f"manifest: timestamp valid, unchanged since {info['time']}")
        else:
            later = sorted(e.seq for e in entries.values() if e.line > count)
            report.checked.append(f"manifest: timestamp valid for its first {count} lines, as of {info['time']}")
            report.notes.append(f"{len(lines) - count} manifest lines were added after its timestamp "
                                f"(chunks {', '.join(map(str, later)) or 'none'}): not covered by it")
        return
    report.problems.append("the manifest was edited after its timestamp: no version of it matches the timestamp")


def verify_session(manifest: Path, stamps: Timestamps) -> Report:
    folder = manifest.parent
    report = Report(session=manifest.name.split(".")[0], folder=folder)
    entries = parse_manifest(manifest.read_text(encoding="utf-8", errors="replace"))
    if not entries:
        report.problems.append("the manifest lists no chunk")
        return report
    check_manifest_token(report, stamps, manifest, entries)
    for seq in sorted(entries):
        entry = entries[seq]
        if entry.state == "started":
            report.problems.append(f"chunk {seq} ({entry.name}) was recorded but never closed nor recovered: lost")
            continue
        what = {"chunk": "chunk", "repaired": "repaired chunk", "cut": "cut chunk"}[entry.state]
        if check_file(report, folder, entry.name, entry.size, entry.sha256, f"{what} {seq}:"):
            check_token(report, stamps, folder / entry.name, f"{what} {seq}:")
        if entry.state == "repaired":
            if check_file(report, folder, entry.original, entry.original_size, entry.original_sha256,
                          f"original of chunk {seq}:"):
                check_token(report, stamps, folder / entry.original, f"original of chunk {seq}:")
        if entry.state == "cut":
            report.notes.append(f"chunk {seq} was cut and could not be repaired: it plays only with repair tools")
    listed = {e.name for e in entries.values()} | {e.original for e in entries.values() if e.original}
    for extra in sorted(p.name for p in folder.glob("*.mp4") if p.name not in listed):
        report.notes.append(f"{extra} is not in the manifest")
    return report


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description="Verify SafeRec sessions downloaded from Google Drive.")
    parser.add_argument("folder", type=Path, help="a session folder, or any folder holding sessions")
    parser.add_argument("--ca-file", help="trusted root certificates (default: certifi's Mozilla list)")
    args = parser.parse_args(argv)
    if not args.folder.is_dir():
        print(f"Not a folder: {args.folder}", file=sys.stderr)
        return 2
    ca_file = args.ca_file
    if not ca_file:
        import certifi
        ca_file = certifi.where()
    stamps = Timestamps(ca_file)
    manifests = sorted(args.folder.rglob("*.0.manifest"))
    if not manifests:
        print(f"No SafeRec session manifest under {args.folder}", file=sys.stderr)
        return 2
    all_ok = True
    for manifest in manifests:
        report = verify_session(manifest, stamps)
        all_ok &= report.ok
        print(f"\nSession {report.session} ({report.folder})")
        for line in report.checked:
            print(f"  ok    {line}")
        for line in report.notes:
            print(f"  note  {line}")
        for line in report.problems:
            print(f"  FAIL  {line}")
        if report.ok:
            print("  => COMPLETE: every listed chunk is present and unchanged")
        else:
            print("  => NOT VERIFIED: see FAIL above")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
