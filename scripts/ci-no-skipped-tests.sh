#!/usr/bin/env bash
# Fails when any test was skipped (or no test report exists), from the Surefire reports of every module.
# Used by the CI and release workflows: on their runners every test must run.
set -euo pipefail

shopt -s nullglob
reports=(*/target/surefire-reports/TEST-*.xml)
if [ ${#reports[@]} -eq 0 ]; then
  echo "No Surefire reports found: the tests did not run." >&2
  exit 1
fi

total=0
skipped=0
for f in "${reports[@]}"; do
  line=$(grep -m1 -o '<testsuite [^>]*>' "$f" || true)
  t=$(echo "$line" | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' || echo 0)
  s=$(echo "$line" | grep -o ' skipped="[0-9]*"' | grep -o '[0-9]*' || echo 0)
  total=$((total + t))
  if [ "$s" -gt 0 ]; then
    echo "Skipped: $s in $f" >&2
    skipped=$((skipped + s))
  fi
done

echo "Tests run: $total, skipped: $skipped (${#reports[@]} reports)"
if [ "$skipped" -gt 0 ]; then
  echo "Tests were skipped; on this runner every test must run." >&2
  exit 1
fi
