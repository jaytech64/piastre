#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jérémy Mailhot
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Brings in the icons a category can carry: one Material Symbols vector drawable
# (Rounded, filled, weight 400) for each name in category-icons.txt.
#
# Run on a development machine after adding a name to the list. The app itself
# never fetches anything: the drawables are committed.
#
# The upstream tint is removed because it points at a theme attribute this app
# does not have; whatever draws the icon gives it its colour.
set -euo pipefail

REF="${MATERIAL_SYMBOLS_REF:-master}"
SOURCE="https://raw.githubusercontent.com/google/material-design-icons/$REF/symbols/android"

here="$(dirname "$(readlink -f "$0")")"
drawables="$here/../core/designsystem/src/main/res/drawable"
mkdir -p "$drawables"

while read -r name; do
    [ -z "$name" ] && continue
    target="$drawables/category_icon_$name.xml"
    {
        echo '<!-- SPDX-FileCopyrightText: Google LLC -->'
        echo '<!-- SPDX-License-Identifier: Apache-2.0 -->'
        curl --fail --silent --show-error \
            "$SOURCE/$name/materialsymbolsrounded/${name}_fill1_24px.xml" |
            perl -0pe 's/\s+android:tint="[^"]*"//'
    } >"$target.part" || {
        rm -f "$target.part"
        echo "no Material Symbol named $name" >&2
        exit 1
    }
    mv "$target.part" "$target"
done <"$here/category-icons.txt"
