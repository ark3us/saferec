"""Tests for check-apk-cert.sh, offline: a fake apksigner first on PATH prints what each
apksigner version prints for an APK.

    uv run --with pytest pytest scripts/test_check_apk_cert.py -q --basetemp=tmp/pytest-cert
"""

import os
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parent / "check-apk-cert.sh"
RELEASE = "0a541d9a8dfc3c06ee9ec231bdd3de307ad9328cfa82aa0ba53ad6b247ec52bc"
OTHER = "790c64d72c8c8bc855219f9609e8885f7082ec18d9f450557a57c87f62fbd15d"


def old_format(*digests):  # apksigner up to 36
    return "".join(f"Signer #{i} certificate DN: CN=Test\n"
                   f"Signer #{i} certificate SHA-256 digest: {d}\n"
                   f"Signer #{i} certificate SHA-1 digest: 00\n"
                   for i, d in enumerate(digests, 1))


def new_format(*digests):  # apksigner 37: one block per scheme
    return "".join(f"{scheme} Signer: certificate DN: CN=Test\n"
                   f"{scheme} Signer: certificate SHA-256 digest: {d}\n"
                   f"{scheme} Signer: certificate SHA-1 digest: 00\n"
                   for scheme, d in zip(["V3.0", "V3.1"], digests))


def check(tmp_path, output, exit_code=0):
    fake = tmp_path / "bin" / "apksigner"
    fake.parent.mkdir()
    fake.write_text(f"#!/usr/bin/env bash\ncat <<'OUT'\n{output}OUT\nexit {exit_code}\n")
    fake.chmod(0o755)
    env = {**os.environ, "PATH": f"{fake.parent}{os.pathsep}{os.environ['PATH']}"}
    return subprocess.run(["bash", str(SCRIPT), "some.apk"], env=env, capture_output=True, text=True)


@pytest.mark.parametrize("output", [old_format(RELEASE), new_format(RELEASE),
                                    new_format(RELEASE, RELEASE)])
def test_accepts_the_release_key(tmp_path, output):
    result = check(tmp_path, output)
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.parametrize("output", [
    old_format(OTHER), new_format(OTHER),
    old_format(RELEASE, OTHER), new_format(RELEASE, OTHER),  # a second signer, or a rotated key
    "Verifies\n",  # no certificate at all
])
def test_rejects_any_other_signature(tmp_path, output):
    assert check(tmp_path, output).returncode == 1


def test_rejects_an_apk_that_does_not_verify(tmp_path):
    assert check(tmp_path, "DOES NOT VERIFY\n", exit_code=1).returncode == 1
