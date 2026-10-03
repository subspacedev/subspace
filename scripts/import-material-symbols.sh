#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Additional permission: see Stores Exception in LICENSE.
#
# Imports Material Symbols Rounded as Android vector drawables (M8.6 spec §2.7).
# Source: google/material-design-icons at a pinned commit, Apache-2.0
# (see THIRD_PARTY.md). Re-run to add an icon: append it to ICONS and run.
#
# Each file's android:tint="?attr/colorControlNormal" is removed: Compose's
# painterResource does not resolve theme attributes in a vector, and Icon()
# applies its own tint anyway.
set -euo pipefail

COMMIT="737e3324305806514d7909874fa1818ae1808232"
BASE="https://raw.githubusercontent.com/google/material-design-icons/${COMMIT}/symbols/android"
OUT="$(cd "$(dirname "$0")/.." && pwd)/core/ui/src/main/res/drawable"

ICONS="add alt_route apps arrow_back arrow_outward battery_saver block bolt category check
chevron_right close cloud_download content_paste contrast dark_mode delete dns download edit
eco expand_more fingerprint folder_open history home info keyboard_arrow_down keyboard_arrow_up light_mode
list_alt lock more_vert network_ping north_east open_in_new palette playlist_add_check power_settings_new progress_activity
public qr_code_scanner refresh restart_alt route search select_all settings shield south_east
speed stop_circle swap_vert sync tune vpn_lock warning"
FILLED="shield home dns settings check"

fetch() { # name suffix outfile
  local url="${BASE}/$1/materialsymbolsrounded/$1$2_24px.xml"
  # perl -0 reads the whole file, so the leading \s+ also eats the newline
  # and indent before the attribute: upstream puts android:tint on its own
  # line ending in `>`, and a line-wise sed left a whitespace-only `   >` line
  # behind. This joins the `>` onto the previous attribute instead.
  curl -fsSL "$url" | perl -0pe 's/\s+android:tint="[^"]*"//g' > "$3"
}

for name in $ICONS; do fetch "$name" "" "${OUT}/sym_${name}.xml"; done
for name in $FILLED; do fetch "$name" "_fill1" "${OUT}/sym_${name}_filled.xml"; done
echo "imported $(echo $ICONS | wc -w) icons and $(echo $FILLED | wc -w) filled variants into ${OUT}"
