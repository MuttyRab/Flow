# Personal Flow Nightly: in-app updates

This fork uses Flow's existing Android in-app updater. It **does not** download and execute code
from the repository; instead, every successful merge to `main` produces a signed APK in the
fork's rolling `nightly` GitHub release. Installed personal nightlies check that release when
opened and periodically (subject to app settings), download the APK in-app, verify it, and
request Android's package-installer approval. No separate manual APK download is needed after
the first installation. **Android may still request confirmation.** Native Kotlin changes
cannot be applied as arbitrary live code patches.

## One-time signing setup

Use a dedicated personal signing key and keep it for the lifetime of the app. Do **not** use
upstream Flow's signing certificate, reuse another app's key, or commit your keystore,
passwords or Base64 secrets to Git. Anyone possessing the private signing key can impersonate
updates for your personal build.

1. Use the provided private signing backup (`flow-personal-signing-backup.zip`) and store it
   securely offline. The contained keystore was generated specifically for your personal fork.
   Do not upload either the keystore or password to the repository as ordinary files.
2. In **MuttyRab/Flow → Settings → Secrets and variables → Actions → Repository secrets**,
   use the values in `github-actions-secret-values.txt` from your private backup. You only
   need these **two secrets**:
   - `NIGHTLY_KEYSTORE_BASE64`: the complete Base64 encoding of the keystore.
   - `NIGHTLY_STORE_PASSWORD`: the keystore password.
   The CI workflow uses the fixed alias `flow-personal` and the same password for the key.
   It pins this key's public SHA-256 certificate fingerprint in the workflow file:
   `037495da46ac51a791b33e4de287e435ab9c0c20075cd39bf69d762b720922fd`.
3. Enable Actions on this fork if GitHub requests it. Push or merge a change to `main` (or manually run **Android CI/CD** on `main`);
   the existing **Android CI/CD** workflow runs checks, unit tests, lint and an APK build.
   On success it publishes the rolling [personal nightly release](https://github.com/MuttyRab/Flow/releases/tag/nightly).
   The workflow deliberately refuses to publish if the signing key is absent or its fingerprint
   does not match the pinned public fingerprint.

## Install and future updates

Install **flow-nightly-<run>.apk** from that personal nightly release once. The package ID
is `io.github.aedev.flow.nightly.mutty`, so it will not replace official Flow or official
Flow Nightly. Future releases from **MuttyRab/Flow** will use the same package and signing key.

When an update is available, Flow's existing update screen downloads and installs the update
through Android's package installer. Android confirmation and "Install unknown apps"
permission may be required. Your preferences remain intact across updates **provided you
do not uninstall the personal app and keep using the same signing key**.

Do not install debug-signed PR preview APKs as the permanent personal build: they use a
different signing key, and GitHub's ephemeral debug key cannot be relied on for updates.

### Limits

- The `nightly` channel uses GitHub Actions run numbers for version ordering. The first
  personal APK must be a successful `main` build (not a local run-number-zero APK).
- If GitHub Actions or release publication fails, there is no newer installable APK to fetch.
- Stable `v*` releases still require separate stable-release signing configuration.
  The personal update workflow here intentionally uses the `nightly` channel.
- In-app checking is subject to network access and update notification preferences; use the
  app's manual update check to check immediately.

## Automatic upstream synchronization

`.github/workflows/upstream-sync.yml` checks `Flow-Tube/Flow` every six hours.
When new upstream commits exist, it merges upstream `main` into this fork's `main`
without force-pushing or discarding personal changes. It verifies the personal app ID,
update repository, signing fingerprint and signing inputs before pushing. An automatic
sync explicitly dispatches **Android CI/CD** because GitHub doesn't trigger push
workflows from commits pushed with `GITHUB_TOKEN`.

Normal pushes to `main`, manual **Android CI/CD** runs on `main`, and upstream
sync dispatches all publish the same signed rolling `nightly` release **only after**
checks, tests, lint and APK build succeed.

Important limitations: conflicting upstream changes or personal-signing identity
changes stop the sync rather than overwriting your fork. GitHub CI errors stop
publication, leaving the previous signed nightly available. This is a scheduled
merge of upstream code, not a guarantee upstream code will always compile safely;
automatic upstream code execution inherits the trust you place in Flow-Tube/Flow.
The scheduled workflow is also available via **Actions → Sync upstream Flow → Run workflow**
for an immediate sync.
