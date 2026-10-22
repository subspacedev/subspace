#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Exercises compare-apks.py on synthetic zips (M10-A spec §6.5: reproducibility
# compares APKs while ignoring only the signature).

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compare="$script_dir/compare-apks.py"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

python3 -I - "$work" <<'PY'
import sys, zipfile, os
w = sys.argv[1]
def make(name, entries):
    with zipfile.ZipFile(os.path.join(w, name), "w") as z:
        for n, data, method in entries:
            zi = zipfile.ZipInfo(n, (2020, 1, 1, 0, 0, 0))
            zi.compress_type = method
            z.writestr(zi, data)
D, S = zipfile.ZIP_DEFLATED, zipfile.ZIP_STORED
base = [("AndroidManifest.xml", b"manifest" * 50, D), ("classes.dex", b"dex" * 100, D)]
make("base.zip", base)
make("same.zip", base)
make("signed.zip", base + [("META-INF/CERT.RSA", b"sig", D), ("META-INF/CERT.SF", b"sf", D)])
make("changed.zip", [base[0], ("classes.dex", b"DEX" * 100, D)])
make("swapped.zip", [base[1], base[0]])
make("stored.zip", [base[0], ("classes.dex", b"dex" * 100, S)])
# Names differing only in case must stay distinct (macOS-safe: never extracted).
make("case-a.zip", base + [("res/A.png", b"a", D), ("res/a.png", b"b", D)])
make("case-b.zip", base + [("res/A.png", b"a", D), ("res/a.png", b"c", D)])
PY

fail=0
expect() { # expected_code description args...
    local want="$1" desc="$2"; shift 2
    local got=0
    python3 -I "$compare" "$@" >"$work/out" 2>&1 || got=$?
    if [ "$got" -ne "$want" ]; then
        echo "FAIL: $desc (exit $got, wanted $want)"; cat "$work/out"; fail=1
    else
        echo "ok: $desc"
    fi
}
z() { echo "$work/$1.zip"; }

expect 0 "identical zips" "$(z base)" "$(z same)"
expect 0 "extra signature files ignored" "$(z base)" "$(z signed)"
expect 1 "changed content" "$(z base)" "$(z changed)"
grep -q 'classes.dex' "$work/out" || { echo "FAIL: output does not name classes.dex"; fail=1; }
expect 1 "entry order swapped" "$(z base)" "$(z swapped)"
expect 1 "stored vs deflated" "$(z base)" "$(z stored)"
expect 1 "case-only-different names compared separately" "$(z case-a)" "$(z case-b)"
grep -q 'res/a.png' "$work/out" || { echo "FAIL: output does not name res/a.png"; fail=1; }
expect 2 "one argument" "$(z base)"

exit "$fail"
