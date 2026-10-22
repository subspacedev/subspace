#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Usage: collect-release-apks.sh <apk-dir> <version> <out-dir>
# Copies the four signed release APKs as subspace-<version>-<abi>.apk and
# writes SHA256SUMS over them (M10-A spec §6). Refuses anything else: a missing
# APK, an "-unsigned" APK (a release must never ship unsigned), or an out-dir
# that already holds files. AGP's output-metadata.json and baselineProfiles/
# next to the APKs are ignored.

set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: collect-release-apks.sh <apk-dir> <version> <out-dir>" >&2
  exit 2
fi

apk_dir="$1"
version="$2"
out_dir="$3"
abis="universal arm64-v8a armeabi-v7a x86_64"

if [ ! -d "$apk_dir" ]; then
  echo "apk dir not found: $apk_dir" >&2
  exit 1
fi

unsigned=""
for f in "$apk_dir"/*-unsigned.apk; do
  [ -e "$f" ] && unsigned="$unsigned $(basename "$f")"
done
if [ -n "$unsigned" ]; then
  echo "unsigned APK(s) present, refusing to release:$unsigned" >&2
  exit 1
fi

for abi in $abis; do
  if [ ! -f "$apk_dir/app-$abi-release.apk" ]; then
    echo "missing signed APK: app-$abi-release.apk" >&2
    exit 1
  fi
done

if [ -d "$out_dir" ] && [ -n "$(ls -A "$out_dir")" ]; then
  echo "out dir is not empty: $out_dir" >&2
  exit 1
fi
mkdir -p "$out_dir"

for abi in $abis; do
  cp "$apk_dir/app-$abi-release.apk" "$out_dir/subspace-$version-$abi.apk"
done

if command -v sha256sum >/dev/null 2>&1; then
  hash_cmd="sha256sum"
else
  hash_cmd="shasum -a 256"
fi
# Bare file names, in a fixed order, so `sha256sum -c` works from the download dir.
(
  cd "$out_dir"
  for abi in $abis; do
    $hash_cmd "subspace-$version-$abi.apk"
  done > SHA256SUMS
)
