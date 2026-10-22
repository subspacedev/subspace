#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
"""Compare two APKs, ignoring signatures and zip metadata (M10-A spec §6.5).

Usage: compare-apks.py <a.apk> <b.apk>
Exit 0 when equal, 1 when they differ (each differing entry is printed),
2 on a usage or read error.

Compared: entry names, order, compression method, CRC, sizes and the compressed
bytes. Ignored: signature files, the APK signing block and other zip metadata
(timestamps, extra fields, attributes).

Entries are compared in memory, in order, keyed by exact name. Real APKs hold
names that differ only in letter case, so extracting them to a case-insensitive
filesystem would silently merge entries; this never extracts.

Ignored: META-INF/*.SF|*.RSA|*.EC|*.DSA, and META-INF/MANIFEST.MF when only one
side has it (v1 signing adds it). The APK Signing Block lives outside the zip
entries, so zipfile never sees it. Everything else, including
META-INF/version-control-info.textproto, must match: name, order, compression
method, CRC, sizes and the raw compressed bytes.
"""
import re
import struct
import sys
import zipfile

SIGNATURE = re.compile(r"META-INF/[^/]+\.(SF|RSA|EC|DSA)\Z")
MANIFEST = "META-INF/MANIFEST.MF"


def raw_bytes(fh, info):
    """Compressed payload, read past the local header (its extra field may
    differ in length from the central directory's)."""
    fh.seek(info.header_offset)
    header = fh.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("bad local header for " + info.filename)
    name_len, extra_len = struct.unpack("<HH", header[26:30])
    fh.seek(info.header_offset + 30 + name_len + extra_len)
    return fh.read(info.compress_size)


def entries(path, drop_manifest):
    with zipfile.ZipFile(path) as z, open(path, "rb") as fh:
        out = []
        for info in z.infolist():
            if SIGNATURE.match(info.filename):
                continue
            if drop_manifest and info.filename == MANIFEST:
                continue
            out.append((info.filename, info.compress_type, info.CRC,
                        info.compress_size, info.file_size,
                        raw_bytes(fh, info)))
        return out


def has_manifest(path):
    with zipfile.ZipFile(path) as z:
        return any(i.filename == MANIFEST for i in z.infolist())


def main(argv):
    if len(argv) != 3:
        print("usage: compare-apks.py <a.apk> <b.apk>", file=sys.stderr)
        return 2
    a_path, b_path = argv[1], argv[2]
    try:
        drop = has_manifest(a_path) != has_manifest(b_path)
        a, b = entries(a_path, drop), entries(b_path, drop)
    except (OSError, zipfile.BadZipFile, ValueError) as e:
        print("error: " + str(e), file=sys.stderr)
        return 2

    diffs = []
    names_a = [e[0] for e in a]
    names_b = [e[0] for e in b]
    if names_a == names_b:
        for x, y in zip(a, b):
            if x[1:5] != y[1:5]:
                diffs.append("%s: method/CRC/size differ" % x[0])
            elif x[5] != y[5]:
                diffs.append("%s: compressed bytes differ" % x[0])
    else:
        from collections import Counter
        ca, cb = Counter(names_a), Counter(names_b)
        for n in sorted((ca - cb).keys()):
            diffs.append("%s: only in %s" % (n, a_path))
        for n in sorted((cb - ca).keys()):
            diffs.append("%s: only in %s" % (n, b_path))
        if ca == cb:
            first = next(i for i, (p, q) in enumerate(zip(names_a, names_b))
                         if p != q)
            diffs.append("entry order differs, first at index %d (%s vs %s)"
                         % (first, names_a[first], names_b[first]))

    for d in diffs:
        print(d)
    return 1 if diffs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
