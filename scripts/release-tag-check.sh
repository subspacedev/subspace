#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Usage: release-tag-check.sh <tag>
# Exit 0 iff <tag> is "v" + versionName from version.properties (M10-A spec §6.4:
# the tag is the release, so it must name the version the build will embed).
# Prints "prerelease=true|false" on stdout for $GITHUB_OUTPUT; a name with a
# "-" suffix (0.1.0-alpha01) is a prerelease.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: release-tag-check.sh <tag>" >&2
  exit 2
fi

tag="$1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
props="$script_dir/../version.properties"

version="$(sed -n 's/^versionName=//p' "$props")"
if [ -z "$version" ]; then
  echo "no versionName in version.properties" >&2
  exit 2
fi

if [ "$tag" != "v$version" ]; then
  echo "tag mismatch: tag is '$tag', version.properties says 'v$version'" >&2
  exit 1
fi

case "$version" in
  *-*) echo "prerelease=true" ;;
  *) echo "prerelease=false" ;;
esac
