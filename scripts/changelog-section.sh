#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Usage: changelog-section.sh <version> [file]   (file defaults to CHANGELOG.md)
# Prints the body under "## [<version>]" up to the next "## " heading, with
# leading and trailing blank lines removed (release notes, M10-A spec §6.4).
# Exit 1 when the heading is absent or its body is blank. The bracketed
# version must match exactly, so 0.1.0 never picks up 0.1.0-alpha02.

set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: changelog-section.sh <version> [file]" >&2
  exit 2
fi

version="$1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
file="${2:-$script_dir/../CHANGELOG.md}"

if [ ! -f "$file" ]; then
  echo "changelog not found: $file" >&2
  exit 1
fi

body="$(awk -v want="## [$version]" '
  /^## / {
    if (found) exit
    # Exact bracket match; anything after "]" (a date) is allowed.
    if (substr($0, 1, length(want)) == want) {
      rest = substr($0, length(want) + 1)
      if (rest == "" || rest ~ /^[ \t]/) found = 1
    }
    next
  }
  found {
    if ($0 ~ /^[ \t\r]*$/) { pending = pending "\n"; next }
    if (started) printf "%s", pending
    pending = ""
    started = 1
    print
  }
  END { if (!found) exit 3 }
' "$file")" || {
  echo "no '## [$version]' heading in $file" >&2
  exit 1
}

if [ -z "$body" ]; then
  echo "the '## [$version]' section in $file is empty" >&2
  exit 1
fi

printf '%s\n' "$body"
