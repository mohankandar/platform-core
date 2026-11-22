#!/usr/bin/env bash
# Fails when any test was skipped (or no test report exists), from the Surefire reports of every module.
# Used by the CI and release workflows: on their runners every test must run.
# Prints the tests per module and the total, counted once per test class report (TEST-*.xml). Reports quote these
# numbers; adding up Maven's "Tests run:" log lines counts every test twice (the class lines and the module totals).
set -euo pipefail

shopt -s nullglob
reports=(*/target/surefire-reports/TEST-*.xml)
if [ ${#reports[@]} -eq 0 ]; then
  echo "No Surefire reports found: the tests did not run." >&2
  exit 1
fi

total=0
skipped=0
declare -A per_module=()
declare -A per_module_skipped=()
for f in "${reports[@]}"; do
  module=${f%%/*}
  line=$(grep -m1 -o '<testsuite [^>]*>' "$f" || true)
  t=$(echo "$line" | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' || echo 0)
  s=$(echo "$line" | grep -o ' skipped="[0-9]*"' | grep -o '[0-9]*' || echo 0)
  total=$((total + t))
  per_module[$module]=$(( ${per_module[$module]:-0} + t ))
  per_module_skipped[$module]=$(( ${per_module_skipped[$module]:-0} + s ))
  if [ "$s" -gt 0 ]; then
    echo "Skipped: $s in $f" >&2
    skipped=$((skipped + s))
  fi
done

for m in $(printf '%s\n' "${!per_module[@]}" | sort); do
  echo "  $m: ${per_module[$m]} tests, ${per_module_skipped[$m]} skipped"
done
echo "Tests run: $total, skipped: $skipped (${#reports[@]} reports)"
if [ "$skipped" -gt 0 ]; then
  echo "Tests were skipped; on this runner every test must run." >&2
  exit 1
fi
