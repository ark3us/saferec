# GitHub Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pushed tag `v<versionName>` makes GitHub Actions build, sign and publish SafeRec as a GitHub Release, under the new application id `net.ark3us.saferec.app`, with unit tests on every push.

**Architecture:**
- **Shared shell scripts carry the logic; workflows only call them.** `scripts/ci-checks.sh` (every check), `scripts/check-apk-cert.sh` (the registered certificate), `scripts/app-version.sh` and `scripts/release-notes.sh` run the same way on the owner's machine, in `release.sh` and on GitHub.
- **Gradle never sees the key in CI.** The `build` job (no secrets) builds an unsigned release; the `publish` job (environment `release`, tags `v*` only) signs it with `apksigner` and runs no Gradle. Locally Gradle signs as today.
- **`scripts/release.sh`** runs every check, including a signed build and the certificate check, before it bumps, commits, tags and pushes.

**Tech Stack:** Bash, GitHub Actions (ubuntu-24.04, Temurin 21), Gradle 9.3.1 wrapper, AGP 9.1.0, Android build-tools `apksigner`/`zipalign`, Python pytest via `uv`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-21-github-release-design.md`

## Global Constraints

- Application id `net.ark3us.saferec.app`. `namespace`, Java packages and class names stay `net.ark3us.saferec`.
- Registered certificate SHA-256, as `apksigner` prints it: `0a541d9a8dfc3c06ee9ec231bdd3de307ad9328cfa82aa0ba53ad6b247ec52bc` (colon form `0A:54:1D:9A:8D:FC:3C:06:EE:9E:C2:31:BD:D3:DE:30:7A:D9:32:8C:FA:82:AA:0B:A5:3A:D6:B2:47:EC:52:BC`). Release SHA-1: `EF:74:3F:7A:22:DE:D3:19:8D:55:70:D1:90:55:86:92:1C:C3:5E:9E`.
- Key alias `cert`. Secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, only in the GitHub environment `release`, deployment rule: tags `v*`.
- Tags are `v<versionName>`; versions are `X.Y` or `X.Y.Z`. Next version: 1.17 (current `versionName = "1.16"`, `versionCode = 17`).
- Assets: `SafeRec-<version>.apk`, `SafeRec-<version>-mapping.txt`, `SHA256SUMS`.
- Always `bash ./gradlew` and `bash scripts/...`, never `./...`: the working copy is synced by Filen, which drops executable bits.
- Every third-party action is pinned to a full commit SHA, with the tag in a comment.
- No secret on a command line, in the terminal or in a log: `apksigner --ks-pass env:…`, `gh secret set` from stdin, no `set -x` in any step that sees a secret.
- Code, comments, docs and commits in English. Commit messages short and clear, no `Co-Authored-By` line.
- Test phone: `ANDROID_SERIAL` pinned to the Galaxy S10+, model checked before any `adb` command.

## Review Focus

- **A secret in a log.** Expected: the publish job's log shows no password and no base64 keystore. Task 5 greps the workflow for `pass:`, `set -x` and `echo "$RELEASE`; Task 7 reads the publish job's log.
- **A tag on a commit that is not on `main`** (pushed from a branch). Expected: the build job refuses it before building. Task 5 adds the step; Task 7 does not test it live (it would need a stray tag on GitHub), so the reviewer checks the step reads `origin/main`.
- **A re-run after the Release is published.** Expected: the run fails, the published Release is untouched. No `--clobber` anywhere (Task 5 grep); `gh release create` refuses an existing release.
- **A failure after the draft was created.** Expected: no draft left behind, and `release.sh --retag` refuses while any Release (draft included) exists. Task 5 adds the clean-up step, Task 4 tests the refusal.
- **A working copy without executable bits.** Expected: every script still runs. Task 4's fixtures write every stub without the executable bit.

---

### Task 1: New application id, pinned by a test

**Files:**
- Modify: `app/build.gradle.kts:45` (`applicationId`)
- Modify: `app/src/main/res/xml/shortcuts.xml:12` (`android:targetPackage`)
- Modify: `scripts/device-tests.sh:21-22` (`PKG`, `ACTIVITY`)
- Test: `app/src/test/java/net/ark3us/saferec/ApplicationIdTest.java`

**Interfaces:**
- Produces: `BuildConfig.APPLICATION_ID == "net.ark3us.saferec.app"` for debug, release and `r8test`.

- [ ] **Step 1: Write the failing tests**

```java
public class ApplicationIdTest {
    @Test public void applicationIdIsTheRegisteredOne() {
        assertEquals("net.ark3us.saferec.app", BuildConfig.APPLICATION_ID);
    }

    // shortcuts.xml cannot use ${applicationId}: its targetPackage is written by hand
    @Test public void shortcutsTargetTheApplicationId() throws Exception {
        // parse src/main/res/xml/shortcuts.xml (unit tests run in app/) with javax.xml DOM,
        // collect android:targetPackage of every <intent>
        assertFalse(targets.isEmpty());
        for (String t : targets) assertEquals(BuildConfig.APPLICATION_ID, t);
    }
}
```

- [ ] **Step 2: Run, expect `applicationIdIsTheRegisteredOne` to fail**

Run: `bash ./gradlew :app:testDebugUnitTest --tests net.ark3us.saferec.ApplicationIdTest`
Expected: 1 failure, `expected:<net.ark3us.saferec[.app]> but was:<net.ark3us.saferec[]>`.

- [ ] **Step 3: Set `applicationId = "net.ark3us.saferec.app"`, run again**

Expected: now `shortcutsTargetTheApplicationId` fails (`net.ark3us.saferec` in `shortcuts.xml`).

- [ ] **Step 4: Set `android:targetPackage="net.ark3us.saferec.app"` in `shortcuts.xml`** (`targetClass` stays `net.ark3us.saferec.QuickStartActivity`), run again

Expected: PASS, both tests.

- [ ] **Step 5: Update `scripts/device-tests.sh`**

`PKG=net.ark3us.saferec.app`; `ACTIVITY="$PKG/net.ark3us.saferec.MainActivity"` (`.MainActivity` would resolve against the new id). Check that no other `$PKG/.` form exists: `grep -n '\$PKG/\.' scripts/device-tests.sh` prints nothing. `bash -n scripts/device-tests.sh` exits 0.

- [ ] **Step 6: Check the built APK**

Run: `bash ./gradlew :app:assembleDebug && ~/Android/Sdk/build-tools/36.1.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | head -1`
Expected: `package: name='net.ark3us.saferec.app' versionCode='17' versionName='1.16' …`. Then the whole unit test suite: `bash ./gradlew testDebugUnitTest` passes.

- [ ] **Step 7: Commit** — `git add app/build.gradle.kts app/src/main/res/xml/shortcuts.xml scripts/device-tests.sh app/src/test/java/net/ark3us/saferec/ApplicationIdTest.java`, message `Move to application id net.ark3us.saferec.app`.

---

### Task 2: Unsigned release switch and the certificate check

**Files:**
- Modify: `app/build.gradle.kts` (a `saferec.unsignedRelease` property next to `powerGesture`, used in `buildTypes.release`)
- Create: `scripts/check-apk-cert.sh`

**Interfaces:**
- Produces: `bash scripts/check-apk-cert.sh <apk>` — exit 0 only if `apksigner verify --print-certs` accepts the APK, it has exactly one signer, and that signer's certificate SHA-256 is the registered one (the constant lives only in this script). On failure prints what it found. Finds `apksigner` on `PATH`, else in the newest `$ANDROID_HOME/build-tools/*/`, else the newest `build-tools/*/` under `sdk.dir` of `local.properties`.
- Produces: Gradle property `saferec.unsignedRelease` (`true`/`false`, default `false`, any other value throws like `saferec.powerGesture`). When `true`, `release` has `signingConfig = null` and `assembleRelease` writes `app/build/outputs/apk/release/app-release-unsigned.apk`. `r8test` keeps the debug signature either way.

- [ ] **Step 1: Write `scripts/check-apk-cert.sh`** (`set -euo pipefail`, usage error without an argument).

- [ ] **Step 2: It rejects the debug APK**

Run: `bash scripts/check-apk-cert.sh app/build/outputs/apk/debug/app-debug.apk; echo "exit $?"`
Expected: a message with the debug certificate's digest, `exit 1`.

- [ ] **Step 3: It accepts a locally signed release**

Run: `bash ./gradlew :app:assembleRelease && bash scripts/check-apk-cert.sh app/build/outputs/apk/release/app-release.apk; echo "exit $?"`
Expected: `exit 0`.

- [ ] **Step 4: Add `saferec.unsignedRelease` to `app/build.gradle.kts`**, with a one-line comment (CI builds unsigned so the key never enters a Gradle process).

- [ ] **Step 5: The unsigned build, then the publish job's signing, by hand**

Run `bash ./gradlew :app:assembleRelease -Psaferec.unsignedRelease=true`.
- `app-release-unsigned.apk` exists, and `check-apk-cert.sh` rejects it (`exit 1`).
- `zipalign -c -P 16 4 app-release-unsigned.apk` passes. If it does not, the publish job must `zipalign -P 16 -f 4` before signing: note it for Task 5.
- Sign it the way Task 5 will: `apksigner sign --ks keystore.jks --ks-key-alias cert --ks-pass env:RELEASE_STORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD --out tmp/signed.apk app-release-unsigned.apk`, with both variables exported from `local.properties` inside one command, never printed.
- `check-apk-cert.sh tmp/signed.apk` gives `exit 0`. Delete `tmp/signed.apk`.

- [ ] **Step 6: A bad value fails**

Run: `bash ./gradlew :app:help -Psaferec.unsignedRelease=yes`
Expected: build fails with `saferec.unsignedRelease must be true or false, got 'yes'`.

- [ ] **Step 7: Commit** — `app/build.gradle.kts scripts/check-apk-cert.sh`, message `Unsigned release switch and certificate check`.

---

### Task 3: CI checks on every push

**Files:**
- Create: `scripts/ci-checks.sh`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: `bash scripts/ci-checks.sh` — from the repository root (it `cd`s there itself): `bash ./gradlew testDebugUnitTest lintDebug`, then `bash scripts/check-guard-refs.sh`, then `mkdir -p tmp && uv run --with pytest --with certifi pytest scripts -q --basetemp=tmp/pytest` (collects `test_verify_session.py` and, after Task 4, `test_release.py`). Exit non-zero on the first failure.

- [ ] **Step 1: Write `scripts/ci-checks.sh`.**

- [ ] **Step 2: Run it locally**

Run: `bash scripts/ci-checks.sh; echo "exit $?"`
Expected: `exit 0`. If `lintDebug` reports errors on the current code, stop and report them to the owner: no lint baseline, no disabled check without their consent.

- [ ] **Step 3: Resolve the action SHAs**

For `actions/checkout`, `actions/setup-java`, `gradle/actions` (`setup-gradle`), `astral-sh/setup-uv`, and for Task 5 `actions/upload-artifact` and `actions/download-artifact`: `gh release view -R <repo> --json tagName --jq .tagName`, then `gh api repos/<repo>/commits/<tag> --jq .sha`. Write `uses: <repo>@<sha> # <tag>`.

- [ ] **Step 4: Write `.github/workflows/ci.yml`**

Triggers `push: branches: ['**']` (tag pushes do not trigger it) and `pull_request`. `permissions: contents: read`. `concurrency` per ref, cancel in progress. One job on `ubuntu-24.04`: checkout, setup-java (Temurin 21), setup-gradle, setup-uv, `bash scripts/ci-checks.sh`.

- [ ] **Step 5: Lint the workflow**

Run: `podman run --rm -v "$PWD:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest`
Expected: no output, exit 0.

- [ ] **Step 6: Commit** — `scripts/ci-checks.sh .github/workflows/ci.yml`, message `CI checks on every push`. Then push the working branch and check the run: `gh run watch` ends with success, and its log lists the unit tests, lint, the guard check and pytest.

---

### Task 4: `release.sh` with its helpers and tests

**Files:**
- Create: `scripts/app-version.sh`, `scripts/release-notes.sh`, `scripts/release.sh`
- Test: `scripts/test_release.py`

**Interfaces:**
- Produces: `bash scripts/app-version.sh` prints `versionName` from `app/build.gradle.kts`; `--code` prints `versionCode`. Paths relative to the script's own directory.
- Produces: `bash scripts/release-notes.sh <tag>` prints Markdown. With a previous tag (`git describe --tags --abbrev=0 <tag>^`): one `- <subject>` line per commit of `<previous>..<tag>`. Without one: `First release distributed from GitHub. See the [README](https://github.com/ark3us/saferec#readme) to install it and check its signature.`
- Produces: `bash scripts/release.sh <version>` and `bash scripts/release.sh --retag`.
  - In order, `<version>` mode does: validate the version; check the git state (`git fetch origin`); run `bash scripts/ci-checks.sh`, `bash ./gradlew :app:assembleRelease` and `bash scripts/check-apk-cert.sh app/build/outputs/apk/release/app-release.apk`; bump; `git commit -m "Version <v>" -- app/build.gradle.kts`; `git tag -a v<v> -m "SafeRec <v>"`; `git push --atomic origin main refs/tags/v<v>`; print `https://github.com/ark3us/saferec/actions`.
  - The git state check refuses: not on `main`, `HEAD` ≠ `origin/main`, anything staged, local changes to `app/build.gradle.kts`, tag existing locally or on `origin`.
  - The version check refuses anything but `X.Y`/`X.Y.Z`, and a version not greater than the current one (`sort -V`).
  - `--retag` needs the tag `v<current versionName>` to exist and `gh release view <tag>` to fail (no Release, draft included). It runs the same git state checks (except the tag one) and the same checks, then deletes the tag locally and on `origin` and creates and pushes it again on `HEAD`.

The test fixture (pytest, `tmp_path`):
- a bare `origin` and a clone on `main` holding:
  - an `app/build.gradle.kts` with only `        versionCode = 17` and `        versionName = "1.16"`;
  - copies of the three real scripts;
  - stubs `gradlew`, `scripts/ci-checks.sh`, `scripts/check-apk-cert.sh` that append their name and arguments to `$STUB_LOG` and exit with `$STUB_GRADLE_EXIT`, `$STUB_CHECKS_EXIT`, `$STUB_CERT_EXIT` (default 0);
- a `gh` stub first on `PATH`, where `gh release view` exits `$STUB_GH_RELEASE_EXIT`;
- every file written without the executable bit.

- [ ] **Step 1: Write the failing tests in `scripts/test_release.py`**

- `test_app_version_prints_name_and_code`: `1.16`, and `17` with `--code`.
- `test_release_bumps_commits_tags_and_pushes`:
  - `release.sh 1.17` exits 0;
  - on `origin`, `main` has `versionName = "1.17"` and `versionCode = 18`, and its last commit is `Version 1.17`, changing only `app/build.gradle.kts`;
  - `git cat-file -t v1.17` on `origin` is `tag`, pointing to that commit;
  - `$STUB_LOG` shows the checks, `assembleRelease` and the certificate check, in that order.
- `test_rejects_malformed_version` (parametrized: `1.17a`, `v1.17`, `1`, `1.17.0.1`, empty) and `test_rejects_version_not_greater` (`1.16`, `1.15.9`): non-zero exit, `origin` unchanged, no local tag, `app/build.gradle.kts` unchanged.
- `test_refuses_when_a_check_fails` (parametrized over the checks, gradle and cert stubs exiting 1): same "nothing happened" assertions.
- `test_refuses_bad_git_state` (parametrized):
  - not on `main`;
  - behind `origin/main` (a commit pushed from a second clone);
  - a staged file;
  - an edited `app/build.gradle.kts`;
  - tag `v1.17` existing locally;
  - tag `v1.17` existing only on `origin`.
- `test_unrelated_local_changes_stay_out`: an edited `README.md` and a mode change of `gradlew` do not stop the release; the commit holds only `app/build.gradle.kts`; both changes are still in the working tree.
- `test_retag_moves_a_tag_without_release`: after a 1.17 release, a new commit pushed to `main`, `STUB_GH_RELEASE_EXIT=1`: `--retag` exits 0 and `v1.17` on `origin` points to the new `HEAD`.
- `test_retag_refuses_when_a_release_exists`: `STUB_GH_RELEASE_EXIT=0`, non-zero exit, tag unchanged.
- `test_release_notes_first_release`: no tag before `v1.17`, the fixed paragraph.
- `test_release_notes_since_previous_tag`: `v1.16`, commits `A` and `B`, `v1.17`: lines `- A` and `- B`, no commit from before `v1.16`.

- [ ] **Step 2: Run, expect failures**

Run: `uv run --with pytest pytest scripts/test_release.py -q --basetemp=tmp/pytest-release`
Expected: every test fails (scripts missing).

- [ ] **Step 3: Write `scripts/app-version.sh` and `scripts/release-notes.sh`;** their tests pass.

- [ ] **Step 4: Write `scripts/release.sh`;** all tests pass.

- [ ] **Step 5: Full check** — `bash scripts/ci-checks.sh` exits 0 (now also running `test_release.py`).

- [ ] **Step 6: Commit** — the four files, message `Release script with its checks`.

---

### Task 5: Release workflow and the `release` environment

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `scripts/ci-checks.sh` (Task 3), `scripts/check-apk-cert.sh` and `saferec.unsignedRelease` (Task 2), `scripts/app-version.sh` and `scripts/release-notes.sh` (Task 4), the action SHAs (Task 3).

- [ ] **Step 1: Write `.github/workflows/release.yml`**

Trigger `push: tags: ['v*']`, top-level `permissions: contents: read`.

- **Job `build`** (`ubuntu-24.04`, no environment). Steps:
  - checkout with `fetch-depth: 0`;
  - `test "${GITHUB_REF_NAME#v}" = "$(bash scripts/app-version.sh)"`;
  - `git merge-base --is-ancestor "$GITHUB_SHA" origin/main`;
  - setup-java (Temurin 21), setup-gradle, setup-uv;
  - `bash scripts/ci-checks.sh`;
  - `bash ./gradlew :app:assembleRelease -Psaferec.unsignedRelease=true`;
  - `bash scripts/release-notes.sh "$GITHUB_REF_NAME" > notes.md`;
  - upload-artifact `release-inputs` (the unsigned APK, `app/build/outputs/mapping/release/mapping.txt`, `notes.md`; `retention-days: 1`).
- **Job `publish`** (`needs: build`, `environment: release`, `permissions: contents: write`). Steps:
  - checkout, shallow: only for `check-apk-cert.sh`;
  - download-artifact;
  - **Sign**, with the three secrets in this step's `env:` only:
    - `umask 077`;
    - decode `RELEASE_KEYSTORE_BASE64` into `$RUNNER_TEMP/release.jks`;
    - take `apksigner` and `zipalign` from the newest `$ANDROID_HOME/build-tools/*`;
    - `zipalign -c -P 16 4` (or align, per Task 2 Step 5);
    - `apksigner sign --ks "$RUNNER_TEMP/release.jks" --ks-key-alias cert --ks-pass env:RELEASE_STORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD --out "SafeRec-$VERSION.apk" <unsigned>`.
  - **Remove the keystore** (`if: always()`): `rm -f "$RUNNER_TEMP/release.jks"`.
  - `bash scripts/check-apk-cert.sh "SafeRec-$VERSION.apk"`.
  - Copy the mapping to `SafeRec-$VERSION-mapping.txt`, then `sha256sum SafeRec-$VERSION.apk SafeRec-$VERSION-mapping.txt > SHA256SUMS`.
  - **Publish**, with `GH_TOKEN: ${{ github.token }}`:
    - `gh release create "$GITHUB_REF_NAME" --draft --verify-tag --title "SafeRec $VERSION" --notes-file notes.md` with the three assets;
    - then `gh release edit "$GITHUB_REF_NAME" --draft=false`.
  - **Remove a draft left by a failure** (`if: failure()`): only when `gh release view "$GITHUB_REF_NAME" --json isDraft --jq .isDraft` prints `true`, run `gh release delete "$GITHUB_REF_NAME" --yes` (the tag stays).

`VERSION` is `${GITHUB_REF_NAME#v}`, set once in the job's environment from the ref.

- [ ] **Step 2: Lint and grep**

- actionlint as in Task 3 Step 5: no output.
- `grep -nE 'pass:|set -x|echo .*RELEASE_|--clobber' .github/workflows/release.yml` prints nothing.

- [ ] **Step 3: Commit** — `.github/workflows/release.yml`, message `Release workflow`.

- [ ] **Step 4: Create the environment** (repository `ark3us/saferec`)

- `gh api -X PUT repos/ark3us/saferec/environments/release --input -`, with a body that turns custom deployment policies on (`{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}`);
- then `gh api -X POST repos/ark3us/saferec/environments/release/deployment-branch-policies -f name='v*' -f type=tag`.
- Check: `gh api repos/ark3us/saferec/environments/release/deployment-branch-policies --jq '.branch_policies[] | [.name,.type]'` prints `["v*","tag"]`.

- [ ] **Step 5: Ask the owner once more, then set the secrets**

- `base64 -w0 keystore.jks | gh secret set RELEASE_KEYSTORE_BASE64 --env release -R ark3us/saferec`;
- each password: `grep '^RELEASE_STORE_PASSWORD=' local.properties | cut -d= -f2- | tr -d '\n' | gh secret set RELEASE_STORE_PASSWORD --env release -R ark3us/saferec`, and the same for `RELEASE_KEY_PASSWORD`.
- Check: `gh secret list --env release -R ark3us/saferec` lists the three names, and `gh secret list -R ark3us/saferec` (repository level) lists none of them.

---

### Task 6: Documentation without Play

**Files:**
- Modify: `README.md`, `AGENTS.md`, `IDEAS.md`, `privacy_policy.html`, `gradle.properties`
- Delete: `play_store_description.txt`

The content is fixed by the spec's part 6. In short:
- **README**:
  - the Play badge becomes a link to `https://github.com/ark3us/saferec/releases/latest`;
  - an install section: download the APK, allow installs from the browser, check `SHA256SUMS` and the certificate SHA-256 in colon form;
  - "Build it yourself": a self-build uses its own `applicationId`, changed together with `targetPackage` in `shortcuts.xml`, and registers its own OAuth client.
- **AGENTS.md**:
  - Release Build covers both workflows, the `release` environment, `release.sh` and `--retag`, the certificate check, and the mapping attached to each Release;
  - plus why the id changed (Play App Signing, the Developer Console's "already in use" answer) and the two fingerprints;
  - "Google Play declaration" is removed;
  - the Build switch is in standby, kept green by CI;
  - every `net.ark3us.saferec` data path becomes `net.ark3us.saferec.app`;
  - the Testing section lists `test_release.py` and `ApplicationIdTest`.
- **IDEAS.md**:
  - "Unit tests on every push" is removed (done);
  - "No Play Store bundle unless…" becomes "No release tag unless…";
  - "The Play build checks itself" loses its Play framing;
  - the refused-camera idea uses `net.ark3us.saferec.app`.
- **privacy_policy.html**: the contact line names the GitHub repository only.
- **gradle.properties**: the comment on `saferec.powerGesture` no longer says "Play build".

- [ ] **Step 1: Edit the files and `git rm play_store_description.txt`.**

- [ ] **Step 2: Check the leftovers**

- `git grep -n -i -E "play store|google play|play console|play\.google" -- ':!docs/superpowers'` prints only AGENTS.md's explanation of the id change (and the Play App Signing facts in it).
- `git grep -n -E "(data/data|data/user/0)/net\.ark3us\.saferec(/|$)"` prints nothing.

- [ ] **Step 3: Commit** — message `Docs for GitHub distribution`.

---

### Task 7: First release and the device check

Needs the owner's steps 1 and 2 of the spec (Developer Console registration, both OAuth clients). Ask the owner whether they are done before starting.

- [ ] **Step 1: Debug build on the phone**

- `adb devices -l`, then set `ANDROID_SERIAL` to the S10+ and check `adb shell getprop ro.product.model`.
- Install the debug build: `bash ./gradlew :app:installDebug`.
- Connect Drive, record 25 s, stop.
- Expected:
  - the recordings screen lists the new session and the sessions the old package uploaded;
  - if instead a second `SafeRec` folder appears on Drive, report it to the owner (spec part 1).

- [ ] **Step 2: A few device scenarios** — `ANDROID_SERIAL=<S10+> bash scripts/device-tests.sh offline_start tsa` passes against the new id.

- [ ] **Step 3: The first release** (ask the owner right before: it publishes)

- Run `bash scripts/release.sh 1.17`, then `gh run watch` on the release run.
- On failure: re-run the job for a passing glitch; for a code fix, commit and push it, then `bash scripts/release.sh --retag`.
- Expected: Release `v1.17` with the three assets and the first-release paragraph.

- [ ] **Step 4: Check what was published**

- In `tmp/release-check/`: `gh release download v1.17 -R ark3us/saferec`, then `sha256sum -c SHA256SUMS` (both OK) and `bash scripts/check-apk-cert.sh SafeRec-1.17.apk` (`exit 0`).
- Read the publish job's log (`gh run view --log --job <publish job id>`): no password, no base64 block.

- [ ] **Step 5: The published APK on the phone** (ask the owner first: replacing the debug build with a differently signed one means `adb uninstall net.ark3us.saferec.app`, which deletes its local data)

- Install `SafeRec-1.17.apk`.
- Expected: it installs without a verification warning, Drive sign-in works (release OAuth client), and a short recording uploads.

- [ ] **Step 6: A mismatched tag is refused** (ask the owner first)

- Push the tag `v9.9` on `main`'s `HEAD`.
- Expected: the build job fails at the version step, and no Release or draft `v9.9` exists.
- Then delete the tag: `git push origin :refs/tags/v9.9 && git tag -d v9.9`.

- [ ] **Step 7: Clean-up questions for the owner**

- Uninstall the old `net.ark3us.saferec` from the phone (every user: `adb uninstall`, then `pm list packages --user N` for each user)?
- Delete `tmp/release-check/`?
