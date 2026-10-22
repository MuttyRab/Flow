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

1. Create a Java keystore on a trusted computer (or use an existing dedicated key):
   ```sh
   keytool -genkeypair -keystore flow-personal-nightly.keystore -alias flow-personal \
     -keyalg RSA -keysize 3072 -validity 36500
   ```
   Back up this file and its passwords offline. The key alias is `flow-personal`.
2. Compute the certificate fingerprint, lowercase hex, **without colons**:
   ```sh
   keytool -list -v -keystore flow-personal-nightly.keystore -alias flow-personal
   ```
   Copy the `SHA256` fingerprint, remove `:`, and make it lowercase.
3. In **MuttyRab/Flow → Settings → Secrets and variables → Actions**, add the repository
   **variable** `NIGHTLY_SIGNER_SHA256` containing that 64-character fingerprint.
4. Add four Actions **secrets**:
   - `NIGHTLY_KEYSTORE_BASE64`: `base64 -w0 flow-personal-nightly.keystore` on Linux,
     or `base64 < flow-personal-nightly.keystore | tr -d '\\n'` on macOS.
   - `NIGHTLY_STORE_PASSWORD`: the keystore password.
   - `NIGHTLY_KEY_ALIAS`: `flow-personal` (or your chosen alias).
   - `NIGHTLY_KEY_PASSWORD`: the alias's key password.
5. Enable Actions on this fork if GitHub requests it. Push or merge a change to `main`;
   the existing **Android CI/CD** workflow runs checks, unit tests, lint and an APK build.
   On success it publishes the rolling [personal nightly release](https://github.com/MuttyRab/Flow/releases/tag/nightly).
   The workflow deliberately refuses to publish if the signing key is absent or its fingerprint
   does not match the pinned repository variable.

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
