#!/usr/bin/env bash
# Tests release-ci-passed.sh with a gh and a sleep of its own (nothing is called, nothing waits).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dir="$(mktemp -d)"
trap 'rm -rf "${dir}"' EXIT
cat > "${dir}/gh" <<'G'
#!/usr/bin/env bash
echo "$*" >> "${STATE}/calls"
n="$(wc -l < "${STATE}/calls")"
[ "${n}" -ge "${GREEN_AT:-999}" ] && { echo 1; exit 0; }
case "${BEFORE}" in none) echo 0;; error) exit 1;; text) echo '{"message":"Bad Gateway"}';; esac
G
cat > "${dir}/sleep" <<'G'
#!/usr/bin/env bash
echo "$1" >> "${STATE}/sleeps"
G
chmod +x "${dir}/gh" "${dir}/sleep"

# expect <name> <answer before green> <green at call, 999 never> <exit> <calls> <seconds waited>
expect() {
  local state="${dir}/state" code=0 calls waited
  rm -rf "${state}"; mkdir "${state}"; touch "${state}/sleeps"
  STATE="${state}" BEFORE="$2" GREEN_AT="$3" GITHUB_REPOSITORY=o/r PATH="${dir}:${PATH}" \
    bash "${here}/release-ci-passed.sh" abc123 main > "${state}/out" 2> "${state}/err" || code=$?
  calls="$(wc -l < "${state}/calls" | tr -d ' ')"
  waited="$(awk '{s+=$1} END {print s+0}' "${state}/sleeps")"
  if [ "${code} ${calls} ${waited}" != "$4 $5 $6" ]; then
    echo "FAILED $1: exit ${code}, ${calls} calls, ${waited} s waited; expected exit $4, $5 calls, $6 s" >&2
    exit 1
  fi
  if [ "${code}" -ne 0 ] && ! grep -q "No successful CI run on main for abc123" "${state}/err"; then
    echo "FAILED $1: the message is missing" >&2
    exit 1
  fi
  grep -q "head_sha=abc123&branch=main&event=push&status=success" "${state}/calls"
  echo "ok: $1"
}
expect "green at once: no waiting"                none  1   0 1  0
expect "the list lags: green at the third answer" none  3   0 3  30
expect "green at the last answer"                 none  13  0 13 180
expect "never listed, or never green: fails"      none  999 1 13 180
expect "GitHub does not answer: fails"            error 999 1 13 180
expect "GitHub's error text is not a pass"        text  999 1 13 180
expect "GitHub recovers"                          error 4   0 4  45
