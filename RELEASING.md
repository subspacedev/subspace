# Releasing Subspace

How a release is cut, signed and verified. The design and its reasoning are in
`ARCHITECTURE.md §14.7` and the M10-A spec; this file is the procedure.

## Cutting a release

1. Set `versionName` in `version.properties` (for example `1.0.0` or `1.0.0-rc01`).
   It is the single source of truth; `versionCode` is derived from it.
2. In `CHANGELOG.md`, add a `## [1.0.0]` section (the same string as `versionName`,
   in square brackets) with the release notes. Its text becomes the GitHub Release
   body. A missing or empty section fails the workflow before anything is published.
3. Merge to `main` through the normal pull request.
4. Tag that commit on `main` and push the tag:
   `git tag v1.0.0 && git push origin v1.0.0`. The tag must be `v` plus the
   `versionName` exactly, or `release.yml` stops at its first step.

`release.yml` then runs the same gates as `ci.yml`, builds the four signed APKs,
checks each against the pinned certificate, writes `SHA256SUMS` and creates the
GitHub Release. A tag with a suffix (`-alpha01`, `-rc01`) is published as a
prerelease. The workflow only publishes; it never pushes commits or edits files.

## Secrets and environment

`release.yml` runs in the GitHub environment `release`, which should be restricted
to tags matching `v*`. It holds four secrets (names only; values never appear in
the repository, logs or artifacts):

- `SUBSPACE_SIGNING_KEYSTORE_BASE64`
- `SUBSPACE_SIGNING_STORE_PASSWORD`
- `SUBSPACE_SIGNING_KEY_ALIAS`
- `SUBSPACE_SIGNING_KEY_PASSWORD`

The release certificate's SHA-256 is public and is committed in
`.github/release-cert.sha256`; every APK must match it before a release is created.

## Local signing

Put the four Gradle properties in `~/.gradle/gradle.properties`, never in the repository:

```
subspace.signing.storeFile=/absolute/path/to/release.jks
subspace.signing.storePassword=...
subspace.signing.keyAlias=...
subspace.signing.keyPassword=...
```

The environment variables `SUBSPACE_SIGNING_STORE_FILE`, `SUBSPACE_SIGNING_STORE_PASSWORD`,
`SUBSPACE_SIGNING_KEY_ALIAS` and `SUBSPACE_SIGNING_KEY_PASSWORD` work too (CI uses them).
A property wins over the matching variable, and a blank value counts as missing. A
relative `storeFile` resolves against the repository root.

- **None set:** `assembleRelease` produces unsigned `app-<abi>-release-unsigned.apk`.
- **All four set:** it produces release-signed (v2 and v3) `app-<abi>-release.apk`.
- **Only some set:** Gradle fails at configuration, for every task, naming the missing
  input. There is no fallback to the debug key.

### Device runs with the key present

With the key in `~/.gradle/gradle.properties`, a local `assembleRelease` emits
release-signed APKs. Android will not install those over a debug-signed install, and
the uninstall that fixes it wipes the app's data. For a data-preserving run over the
debug-signed install, either build with the signing properties unset (and unset the
environment variables), or re-sign the file with the debug key; `apksigner sign`
replaces any existing signature:

```
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android app-arm64-v8a-release.apk
```

`apksigner` is at `$ANDROID_HOME/build-tools/<ver>/apksigner`. Check the certificate matches the installed app before `adb install -r`.

## Version codes

```
versionCode = major * 40_000_000 + minor * 400_000 + patch * 4_000
            + stageValue * 10 + abiOffset
```

| Stage | stageValue | | ABI | abiOffset |
|---|---|---|---|---|
| `alphaNN` | 0 + NN | | universal | 0 |
| `betaNN` | 100 + NN | | armeabi-v7a | 1 |
| `rcNN` | 200 + NN | | arm64-v8a | 2 |
| final | 300 | | x86_64 | 3 |

Major is capped at 51; minor and patch are 0 to 99; the prerelease number is 1 to 99.
The build fails outside those ranges.

A split APK has a higher code than the universal APK of the same release (offsets 1 to 3
against 0). Installing the **universal** APK over the **split** APK of the same release is
therefore a downgrade, and Android refuses it. Pick one flavour and stay on it, or
uninstall first (which loses the data). Going from universal to a split of the same
release is an upgrade and works.

## Which APK to download

Each release attaches `subspace-<version>-<abi>.apk`: four APKs (one per ABI, plus a universal build) plus `SHA256SUMS`.
Check the download with `sha256sum -c SHA256SUMS` (`shasum -a 256 -c SHA256SUMS` on macOS).

| File | Size (0.1.0-alpha01) | Use it for |
|---|---|---|
| `arm64-v8a` | 17.85 MB | nearly every phone made in the last several years |
| `armeabi-v7a` | 18.40 MB | older 32-bit ARM phones |
| `x86_64` | 18.95 MB | emulators and x86 Chromebooks |
| `universal` | 45.65 MB | when unsure; works everywhere, about two and a half times the size |

32-bit x86 is not built. Native libraries are stored compressed, which is why the
splits are small.

## Verifying a published APK

Reproducibility means two builds of the same commit, from checkouts at different
paths with the same toolchain, give byte-identical unsigned APKs. A signed APK
differs from its unsigned build in its signature files and the APK signing block,
which `scripts/compare-apks.py` ignores along with other zip metadata (timestamps,
extra fields, attributes). It compares entry names, order, method, CRC, sizes and
compressed bytes.

Two caveats up front:

- **Byte identity is guaranteed only on the CI Linux toolchain** (JDK 17 and 21
  Temurin, `platforms;android-37.1`, `build-tools;37.0.0`, NDK 28.2.13676358, Gradle
  from the wrapper; see `.github/actions/setup-android-build`). A macOS rebuild was
  identical Mac to Mac, but across operating systems it is unverified; do not expect a
  Mac build to match a release.
- **You need a git checkout of the tag, not a source archive.** AGP embeds the commit in
  `META-INF/version-control-info.textproto`, so a tree without `.git` differs in that entry.

**Option 1, in CI:** run the `Reproducible build` workflow (`reproducible.yml`) with the
`tag` input set to the release tag. It builds the tag unsigned and compares each APK
attached to the release with the local build. It uses the tag's own tooling, so only
tags created after M10-A can be verified this way.

**Option 2, locally on the Linux toolchain:**

```
git clone --recurse-submodules --branch v1.0.0 https://github.com/subspacedev/subspace && cd subspace
scripts/fetch-native.sh
# Run with the signing properties and SUBSPACE_SIGNING_* variables unset.
./gradlew --no-build-cache --no-configuration-cache :app:assembleRelease
python3 scripts/compare-apks.py \
  app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk \
  subspace-1.0.0-arm64-v8a.apk
```

If you build with signing configured, the file is the signed `app-<abi>-release.apk`
instead; `compare-apks.py` ignores signatures either way, but unsetting the inputs keeps
the `-unsigned` name used above.

Verifying an old tag months later may run on a newer JDK patch or runner image than the
release used, so a mismatch on an old tag should first be retried with the tag-era
toolchain before it is treated as a real difference.

Exit 0 means equal apart from signatures; each differing entry is printed otherwise.
To confirm who signed the download, compare `apksigner verify --print-certs` with
`.github/release-cert.sha256`.

## The one-time key switch

Builds so far, including the one on the owner's phone, are signed with the debug key.
Android will not install a release-signed build over them. The first release-signed
install therefore needs an **uninstall, which deletes the app's data** (servers,
subscriptions, settings). Export what matters first.

The switch also changes the device's HWID. `ANDROID_ID`, from which
`ARCHITECTURE.md §A.4.1` derives it, is scoped to the signing key, so the
release-signed app takes **one more device slot** with providers that enforce a
limit. Free the old slot in the provider's panel if the limit is tight. This happens
once, for the owner and any testers, at a time of their choosing.
