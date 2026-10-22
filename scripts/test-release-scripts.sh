#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Exercises release-tag-check.sh, changelog-section.sh and collect-release-apks.sh
# on fixtures (M10-A spec §6.4: tag, changelog and asset rules for a release).

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fails=0
fail() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

# expect_status <want> <label> <command...>; stdout of the command is kept in $out.
expect_status() {
  local want="$1" label="$2" got=0
  shift 2
  out="$("$@" 2>"$work/stderr")" || got=$?
  if [ "$got" -ne "$want" ]; then
    fail "$label: expected exit $want, got $got"
  fi
}

# --- release-tag-check.sh ---------------------------------------------------
tag_check="$script_dir/release-tag-check.sh"
current="$(sed -n 's/^versionName=//p' "$repo_root/version.properties")"

expect_status 0 "matching tag" "$tag_check" "v$current"
case "$current" in
  *-*) want_pre=true ;;
  *) want_pre=false ;;
esac
[ "$out" = "prerelease=$want_pre" ] || fail "matching tag: expected prerelease=$want_pre, got '$out'"

expect_status 1 "wrong version" "$tag_check" "v9.9.9"
[ -z "$out" ] || fail "wrong version: must print nothing on stdout, got '$out'"
grep -q "v9.9.9" "$work/stderr" && grep -q "v$current" "$work/stderr" \
  || fail "wrong version: message must show both tags"
expect_status 1 "no v prefix" "$tag_check" "$current"
expect_status 2 "no argument (usage error)" "$tag_check"

# --- changelog-section.sh ---------------------------------------------------
section="$script_dir/changelog-section.sh"
cat > "$work/CHANGELOG.md" <<'MD'
# Changelog

## [Unreleased]

## [0.1.0]

First stable line.

- second line

## [0.1.0-alpha02]
Alpha two body.

## [0.1.0-alpha01]

## [0.0.9]

   

## [0.0.8]
Last one, no trailing heading.
MD

expect_status 0 "stable section" "$section" 0.1.0 "$work/CHANGELOG.md"
[ "$out" = "$(printf 'First stable line.\n\n- second line')" ] || fail "stable section body: '$out'"
expect_status 0 "alpha02 section" "$section" 0.1.0-alpha02 "$work/CHANGELOG.md"
[ "$out" = "Alpha two body." ] || fail "alpha02 body: '$out'"
expect_status 0 "last section" "$section" 0.0.8 "$work/CHANGELOG.md"
[ "$out" = "Last one, no trailing heading." ] || fail "last body: '$out'"
expect_status 1 "absent version" "$section" 0.1.0-alpha03 "$work/CHANGELOG.md"
expect_status 1 "empty body" "$section" 0.1.0-alpha01 "$work/CHANGELOG.md"
expect_status 1 "whitespace-only body" "$section" 0.0.9 "$work/CHANGELOG.md"
expect_status 1 "empty Unreleased" "$section" Unreleased "$work/CHANGELOG.md"
expect_status 1 "missing file" "$section" 0.1.0 "$work/nope.md"

# --- collect-release-apks.sh ------------------------------------------------
collect="$script_dir/collect-release-apks.sh"
abis="universal arm64-v8a armeabi-v7a x86_64"

if command -v sha256sum >/dev/null 2>&1; then
  verify() { sha256sum -c SHA256SUMS >/dev/null; }
else
  verify() { shasum -a 256 -c SHA256SUMS >/dev/null; }
fi

make_apks() {
  mkdir -p "$1"
  for abi in $abis; do echo "apk $abi" > "$1/app-$abi-release.apk"; done
  # AGP writes these next to the APKs; the collector must ignore them.
  echo '{}' > "$1/output-metadata.json"
  mkdir -p "$1/baselineProfiles"
}

make_apks "$work/ok"
expect_status 0 "all four APKs" "$collect" "$work/ok" 1.2.3 "$work/out-ok"
for abi in $abis; do
  [ -f "$work/out-ok/subspace-1.2.3-$abi.apk" ] || fail "missing renamed asset for $abi"
done
[ "$(find "$work/out-ok" -type f | wc -l | tr -d ' ')" = "5" ] || fail "out dir must hold exactly 4 APKs + SHA256SUMS"
[ "$(wc -l < "$work/out-ok/SHA256SUMS" | tr -d ' ')" = "4" ] || fail "SHA256SUMS must have 4 lines"
(cd "$work/out-ok" && verify) || fail "checksum verification failed"
grep -q "/" "$work/out-ok/SHA256SUMS" && fail "SHA256SUMS must use bare file names"

make_apks "$work/missing"
rm "$work/missing/app-x86_64-release.apk"
expect_status 1 "one APK missing" "$collect" "$work/missing" 1.2.3 "$work/out-missing"

make_apks "$work/unsigned"
echo u > "$work/unsigned/app-arm64-v8a-release-unsigned.apk"
expect_status 1 "unsigned APK present" "$collect" "$work/unsigned" 1.2.3 "$work/out-unsigned"

make_apks "$work/only-unsigned"
rm "$work/only-unsigned/app-universal-release.apk"
echo u > "$work/only-unsigned/app-universal-release-unsigned.apk"
expect_status 1 "unsigned replaces signed" "$collect" "$work/only-unsigned" 1.2.3 "$work/out-only-unsigned"

mkdir -p "$work/out-busy"
echo x > "$work/out-busy/stale"
expect_status 1 "non-empty out dir" "$collect" "$work/ok" 1.2.3 "$work/out-busy"

if [ "$fails" -ne 0 ]; then
  echo "$fails release-script test(s) failed" >&2
  exit 1
fi
echo "release-script tests passed"
