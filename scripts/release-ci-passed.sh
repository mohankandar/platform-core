#!/usr/bin/env bash
# The release guard's question (release.yml): has the commit's own CI run on this branch passed?
#   release-ci-passed.sh <commit> <branch>
# GitHub's run list can lag behind a run that just finished (P058), so it is asked every 15 seconds for up to 3 minutes.
# Passes only on a successful run; a missing or failed run, or a GitHub that does not answer, fails.
set -u
sha="$1"
branch="$2"
for attempt in $(seq 1 13); do
  runs="$(gh api "repos/${GITHUB_REPOSITORY}/actions/workflows/ci.yml/runs?head_sha=${sha}&branch=${branch}&event=push&status=success&per_page=1" --jq '.total_count')" || runs=""
  if [[ "${runs}" =~ ^[1-9][0-9]*$ ]]; then
    echo "commit ${sha} is on ${branch}; CI there passed"
    exit 0
  fi
  if [ "${attempt}" -lt 13 ]; then
    echo "no successful CI run on ${branch} listed yet; asking again in 15 seconds"
    sleep 15
  fi
done
echo "No successful CI run on ${branch} for ${sha}. Wait for its CI on the merge commit to pass, then tag again with a new version." >&2
exit 1
