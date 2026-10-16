#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
# Fetches pinned native artifacts. The binaries are gitignored (§10.7);
# this script is the committed, reproducible way to obtain them.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

LIBXRAY_VERSION="v26.10.10"
LIBXRAY_COMMIT="c0cc8d4a86d32e71e2b4e9e6377723597a069a3d"
LIBXRAY_SHA256="aad01c82eb82e98702838f7b4ac3f33dba1ae5ff4102538e5ca7f05bbab57437"
DEST="core/xray/libs"

mkdir -p "$DEST" .native-cache

url="https://github.com/XTLS/libXray/releases/download/${LIBXRAY_VERSION}/libxray-android.zip"
archive=".native-cache/libxray-${LIBXRAY_VERSION}.zip"

if [ ! -f "$archive" ]; then
    echo "Downloading libXray ${LIBXRAY_VERSION}..."
    curl -fL --progress-bar -o "$archive" "$url"
fi

echo "SHA-256: $(shasum -a 256 "$archive" | cut -d' ' -f1)"

actual=$(shasum -a 256 "$archive" | cut -d' ' -f1)
if [ "$actual" != "$LIBXRAY_SHA256" ]; then
    echo "CHECKSUM MISMATCH for $archive" >&2
    echo "  expected: $LIBXRAY_SHA256" >&2
    echo "  actual:   $actual" >&2
    exit 1
fi

unzip -o "$archive" -d .native-cache/libxray
find .native-cache/libxray -name '*.aar' -exec cp {} "$DEST/libxray.aar" \;

echo "Wrote $DEST/libxray.aar"
