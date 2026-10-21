"""Tests for publish-release.sh, offline: a fake gh keeps the repository's Releases in a JSON
file and logs every call.

    uv run --with pytest pytest scripts/test_publish_release.py -q --basetemp=tmp/pytest-publish
"""

import json
import os
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parent / "publish-release.sh"
# gh api --paginate …/releases prints the Releases; gh api -X DELETE …/releases/<id> removes one;
# gh release create <tag> … adds a published one, refusing a tag that has one (as gh does)
FAKE_GH = r'''#!/usr/bin/env python3
import json, os, sys
state = os.environ["FAKE_RELEASES"]
releases = json.load(open(state))
args = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a") as log:
    log.write(" ".join(args) + "\n")
if args[:2] == ["api", "--paginate"]:
    print(json.dumps(releases))
elif args[:3] == ["api", "-X", "DELETE"]:
    rid = int(args[3].rsplit("/", 1)[1])
    json.dump([r for r in releases if r["id"] != rid], open(state, "w"))
elif args[:2] == ["release", "create"]:
    tag = args[2]
    if any(r["tag_name"] == tag and not r["draft"] for r in releases):
        sys.exit("a release with the same tag name already exists")
    releases.append({"id": 100 + len(releases), "tag_name": tag, "draft": False})
    json.dump(releases, open(state, "w"))
else:
    sys.exit(f"unexpected gh call: {args}")
'''


@pytest.fixture
def gh(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    fake = bin_dir / "gh"
    fake.write_text(FAKE_GH)
    fake.chmod(0o755)
    state = tmp_path / "releases.json"
    log = tmp_path / "gh.log"
    env = {**os.environ, "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
           "FAKE_RELEASES": str(state), "FAKE_LOG": str(log), "GH_REPO": "ark3us/saferec"}

    class Gh:
        def set(self, releases):
            state.write_text(json.dumps(releases))

        def releases(self):
            return json.loads(state.read_text())

        def calls(self):
            return log.read_text().splitlines() if log.exists() else []

        def run(self, *args):
            return subprocess.run(["bash", str(SCRIPT), *args], env=env, cwd=tmp_path,
                                  capture_output=True, text=True)

    return Gh()


def publish(gh, tag):
    return gh.run("publish", tag, f"SafeRec {tag[1:]}", "notes.md", "SafeRec.apk", "SHA256SUMS")


def creates(gh):
    return [c for c in gh.calls() if c.startswith("release create")]


def test_publishes_once_when_the_tag_has_no_release(gh):
    gh.set([{"id": 1, "tag_name": "v1.17", "draft": False}])

    result = publish(gh, "v1.18")

    assert result.returncode == 0, result.stdout + result.stderr
    assert gh.releases()[-1] == {"id": 101, "tag_name": "v1.18", "draft": False}
    [create] = creates(gh)
    assert "--draft" not in create.split()  # gh keeps it a draft while uploading, by itself
    assert "SafeRec.apk SHA256SUMS" in create


# A re-run after the Release was published: the run fails, the published Release stays as it is
def test_refuses_a_tag_with_a_published_release(gh):
    gh.set([{"id": 1, "tag_name": "v1.17", "draft": False}])

    result = publish(gh, "v1.17")

    assert result.returncode != 0
    assert "already has a Release" in result.stderr
    assert creates(gh) == []
    assert gh.releases() == [{"id": 1, "tag_name": "v1.17", "draft": False}]


def test_refuses_a_tag_with_a_draft(gh):
    gh.set([{"id": 2, "tag_name": "v1.18", "draft": True}])

    result = publish(gh, "v1.18")

    assert result.returncode != 0
    assert "already has a Release" in result.stderr
    assert creates(gh) == []


# A tag can have a published Release and a draft at once: drafts go by id, never by tag name
def test_clean_drafts_deletes_only_the_drafts_of_the_tag(gh):
    gh.set([{"id": 1, "tag_name": "v1.17", "draft": False},
            {"id": 2, "tag_name": "v1.17", "draft": True},
            {"id": 3, "tag_name": "v1.16", "draft": True}])

    result = gh.run("clean-drafts", "v1.17")

    assert result.returncode == 0, result.stdout + result.stderr
    assert gh.releases() == [{"id": 1, "tag_name": "v1.17", "draft": False},
                             {"id": 3, "tag_name": "v1.16", "draft": True}]


def test_clean_drafts_without_drafts_changes_nothing(gh):
    gh.set([{"id": 1, "tag_name": "v1.17", "draft": False}])

    assert gh.run("clean-drafts", "v1.17").returncode == 0
    assert gh.releases() == [{"id": 1, "tag_name": "v1.17", "draft": False}]
    assert not [c for c in gh.calls() if "DELETE" in c]
