#!/usr/bin/env bash
# Reads changed paths (one per line) and prints "true" when every one is documentation: docs/**, *.md outside any src/
# folder. Prints "false" otherwise, and for no paths at all. Used by the CI workflow's verify job (P052).
set -euo pipefail

seen=false
while IFS= read -r path || [ -n "$path" ]; do
  [ -z "$path" ] && continue
  seen=true
  case "$path" in
    docs/*) ;;
    */src/* | src/*) echo false; exit 0 ;;
    *.md | README*) ;;
    *) echo false; exit 0 ;;
  esac
done
echo "$seen"
