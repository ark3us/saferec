"""Tests for release.sh, app-version.sh and release-notes.sh, offline: each test runs them in a
throwaway clone with a bare origin, and stubs stand for Gradle, the checks and gh.

    uv run --with pytest pytest scripts/test_release.py -q --basetemp=tmp/pytest-release
"""

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).parent
REAL_SCRIPTS = ["release.sh", "app-version.sh", "release-notes.sh"]
GRADLE = """android {
    defaultConfig {
        versionCode = 17
        versionName = "1.16"
    }
}
"""
# Stands for a command release.sh runs: logs its name and arguments, exits with $<var>
STUB = """#!/usr/bin/env bash
echo "{name} $*" >> "$STUB_LOG"
exit "${{{var}:-0}}"
"""
# gh release list: prints the tags in $STUB_GH_RELEASES (drafts included), exits $STUB_GH_EXIT
GH_STUB = """#!/usr/bin/env bash
if [[ $1 == release && $2 == list ]]; then
    [[ -n ${STUB_GH_RELEASES:-} ]] && printf '%s\\n' $STUB_GH_RELEASES
    exit "${STUB_GH_EXIT:-0}"
fi
exit 0
"""
FIRST_RELEASE_NOTES = ("First release distributed from GitHub. See the "
                       "[README](https://github.com/ark3us/saferec#readme) to install it and "
                       "check its signature.")


@dataclass
class Repo:
    work: Path
    origin: Path
    tmp: Path
    env: dict
    log: Path

    def git(self, *args, cwd=None):
        return subprocess.run(["git", *args], cwd=cwd or self.work, env=self.env, check=True,
                              capture_output=True, text=True).stdout.strip()

    def origin_git(self, *args):
        return self.git(*args, cwd=self.origin)

    def run(self, script, *args, **env):
        return subprocess.run(["bash", f"scripts/{script}", *args], cwd=self.work,
                              env={**self.env, **env}, capture_output=True, text=True)

    def release(self, *args, **env):
        return self.run("release.sh", *args, **env)

    def second_clone(self):
        other = self.tmp / "other"
        if not other.exists():
            self.git("clone", "-q", str(self.origin), str(other), cwd=self.tmp)
        return other

    def commit_file(self, name, text, cwd=None):
        cwd = cwd or self.work
        (cwd / name).write_text(text)
        self.git("add", name, cwd=cwd)
        self.git("commit", "-q", "-m", f"Edit {name}", cwd=cwd)

    def snapshot(self):
        return (self.origin_git("rev-parse", "main"), self.git("tag", "--list"),
                self.origin_git("tag", "--list"), (self.work / "app/build.gradle.kts").read_text())

    def logged(self):
        return self.log.read_text().splitlines() if self.log.exists() else []


def write(path, text, mode=0o644):
    path.write_text(text)
    path.chmod(mode)


@pytest.fixture
def repo(tmp_path):
    env = {**os.environ,
           "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
           "GIT_AUTHOR_NAME": "Test", "GIT_AUTHOR_EMAIL": "test@example.com",
           "GIT_COMMITTER_NAME": "Test", "GIT_COMMITTER_EMAIL": "test@example.com",
           "STUB_LOG": str(tmp_path / "stub.log")}
    for var in ("STUB_CHECKS_EXIT", "STUB_GRADLE_EXIT", "STUB_CERT_EXIT", "STUB_GH_RELEASES",
                "STUB_GH_EXIT"):
        env.pop(var, None)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    write(bin_dir / "gh", GH_STUB, 0o755)
    env["PATH"] = f"{bin_dir}{os.pathsep}{env['PATH']}"

    origin = tmp_path / "origin.git"
    subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(origin)], env=env, check=True)
    work = tmp_path / "work"
    subprocess.run(["git", "init", "-q", "-b", "main", str(work)], env=env, check=True)
    repo = Repo(work, origin, tmp_path, env, tmp_path / "stub.log")

    # Every file without the executable bit, as in a working copy synced by Filen
    (work / "app").mkdir()
    write(work / "app/build.gradle.kts", GRADLE)
    (work / "scripts").mkdir()
    for name in REAL_SCRIPTS:
        write(work / "scripts" / name, (SCRIPTS / name).read_text())
    write(work / "gradlew", STUB.format(name="gradlew", var="STUB_GRADLE_EXIT"))
    write(work / "scripts/ci-checks.sh", STUB.format(name="ci-checks", var="STUB_CHECKS_EXIT"))
    write(work / "scripts/check-apk-cert.sh", STUB.format(name="check-apk-cert", var="STUB_CERT_EXIT"))
    write(work / "README.md", "SafeRec\n")
    repo.git("add", "-A")
    repo.git("commit", "-q", "-m", "Initial")
    repo.git("remote", "add", "origin", str(origin))
    repo.git("push", "-q", "-u", "origin", "main")
    return repo


def test_app_version_prints_name_and_code(repo):
    assert repo.run("app-version.sh").stdout.strip() == "1.16"
    assert repo.run("app-version.sh", "--code").stdout.strip() == "17"


def test_release_bumps_commits_tags_and_pushes(repo):
    result = repo.release("1.17")

    assert result.returncode == 0, result.stdout + result.stderr
    gradle = repo.origin_git("show", "main:app/build.gradle.kts")
    assert 'versionName = "1.17"' in gradle
    assert "versionCode = 18" in gradle
    assert repo.origin_git("log", "-1", "--format=%s", "main") == "Version 1.17"
    assert repo.origin_git("show", "--name-only", "--format=", "main").split() == ["app/build.gradle.kts"]
    assert repo.origin_git("cat-file", "-t", "v1.17") == "tag"
    assert repo.origin_git("rev-parse", "v1.17^{commit}") == repo.origin_git("rev-parse", "main")
    logged = repo.logged()
    assert [line.split()[0] for line in logged] == ["ci-checks", "gradlew", "check-apk-cert"]
    assert ":app:assembleRelease" in logged[1]
    assert "app/build/outputs/apk/release/app-release.apk" in logged[2]


def assert_nothing_happened(repo, before, result):
    assert result.returncode != 0
    assert repo.snapshot() == before


@pytest.mark.parametrize("version", ["1.17a", "v1.17", "1", "1.17.0.1", ""])
def test_rejects_malformed_version(repo, version):
    before = repo.snapshot()
    assert_nothing_happened(repo, before, repo.release(version))


@pytest.mark.parametrize("version", ["1.16", "1.15.9"])
def test_rejects_version_not_greater(repo, version):
    before = repo.snapshot()
    assert_nothing_happened(repo, before, repo.release(version))


@pytest.mark.parametrize("failing", ["STUB_CHECKS_EXIT", "STUB_GRADLE_EXIT", "STUB_CERT_EXIT"])
def test_refuses_when_a_check_fails(repo, failing):
    before = repo.snapshot()
    assert_nothing_happened(repo, before, repo.release("1.17", **{failing: "1"}))


def off_main(repo):
    repo.git("checkout", "-q", "-b", "other")


def behind_origin(repo):
    repo.commit_file("README.md", "newer\n", cwd=repo.second_clone())
    repo.git("push", "-q", "origin", "main", cwd=repo.second_clone())


def staged_file(repo):
    (repo.work / "notes.txt").write_text("staged\n")
    repo.git("add", "notes.txt")


def edited_gradle(repo):
    with (repo.work / "app/build.gradle.kts").open("a") as f:
        f.write("// edited\n")


def tag_local(repo):
    repo.git("tag", "v1.17")


def tag_on_origin_only(repo):
    other = repo.second_clone()
    repo.git("tag", "v1.17", cwd=other)
    repo.git("push", "-q", "origin", "v1.17", cwd=other)


@pytest.mark.parametrize("setup", [off_main, behind_origin, staged_file, edited_gradle,
                                   tag_local, tag_on_origin_only])
def test_refuses_bad_git_state(repo, setup):
    setup(repo)
    before = repo.snapshot()
    assert_nothing_happened(repo, before, repo.release("1.17"))


def test_unrelated_local_changes_stay_out(repo):
    (repo.work / "README.md").write_text("edited, not committed\n")
    (repo.work / "gradlew").chmod(0o755)

    result = repo.release("1.17")

    assert result.returncode == 0, result.stdout + result.stderr
    assert repo.origin_git("show", "--name-only", "--format=", "main").split() == ["app/build.gradle.kts"]
    assert repo.git("diff", "--name-only").split() == ["README.md", "gradlew"]


def released_then_fixed(repo):
    result = repo.release("1.17")
    assert result.returncode == 0, result.stdout + result.stderr
    repo.commit_file("README.md", "fix\n")
    repo.git("push", "-q", "origin", "main")


def test_retag_moves_a_tag_without_release(repo):
    released_then_fixed(repo)

    result = repo.release("--retag", STUB_GH_RELEASES="v1.16")

    assert result.returncode == 0, result.stdout + result.stderr
    assert repo.origin_git("cat-file", "-t", "v1.17") == "tag"
    assert repo.origin_git("rev-parse", "v1.17^{commit}") == repo.git("rev-parse", "HEAD")


# A failing gh must not look like "no Release": the tag of a published Release never moves
@pytest.mark.parametrize("gh_env", [{"STUB_GH_RELEASES": "v1.16 v1.17"}, {"STUB_GH_EXIT": "1"}])
def test_retag_refuses_when_a_release_may_exist(repo, gh_env):
    released_then_fixed(repo)
    tagged = repo.origin_git("rev-parse", "v1.17^{commit}")

    result = repo.release("--retag", **gh_env)

    assert result.returncode != 0
    assert repo.origin_git("rev-parse", "v1.17^{commit}") == tagged


def test_retag_refuses_without_a_tag(repo):
    before = repo.snapshot()
    assert_nothing_happened(repo, before, repo.release("--retag"))


def test_failed_push_is_undone(repo):
    write(repo.origin / "hooks/pre-receive", "#!/bin/sh\nexit 1\n", 0o755)
    before = repo.snapshot()

    assert_nothing_happened(repo, before, repo.release("1.17"))
    assert repo.git("log", "-1", "--format=%s") == "Initial"


def test_release_notes_first_release(repo):
    repo.git("tag", "-a", "v1.17", "-m", "SafeRec 1.17")
    assert repo.run("release-notes.sh", "v1.17").stdout.strip() == FIRST_RELEASE_NOTES


def test_release_notes_since_previous_tag(repo):
    repo.git("tag", "-a", "v1.16", "-m", "SafeRec 1.16")
    repo.commit_file("a.txt", "a\n")
    repo.git("commit", "-q", "--amend", "-m", "A")
    repo.commit_file("b.txt", "b\n")
    repo.git("commit", "-q", "--amend", "-m", "B")
    repo.git("tag", "-a", "v1.17", "-m", "SafeRec 1.17")

    assert repo.run("release-notes.sh", "v1.17").stdout.splitlines() == ["- B", "- A"]
