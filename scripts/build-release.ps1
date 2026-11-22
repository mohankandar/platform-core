<#
Builds a released tag of platform-core into a separate local Maven repository, for offline builds and install
bundles. It checks the tag out in a temporary worktree and never touches ~/.m2.

  scripts/build-release.ps1 -Tag platform-core-v0.1.0 -Repository C:\path\to\local-repo
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Repository
)
$ErrorActionPreference = 'Stop'

if ($Tag -notmatch '^platform-core-v\d+\.\d+\.\d+$') {
    throw "-Tag must be a release tag platform-core-vX.Y.Z"
}
New-Item -ItemType Directory -Force -Path $Repository | Out-Null
$repo = (Resolve-Path $Repository).Path
$m2 = Join-Path $HOME '.m2'
if ($repo.TrimEnd('\').StartsWith($m2, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "-Repository must not be ~/.m2"
}

$root = (git rev-parse --show-toplevel).Trim()
git -C $root rev-parse -q --verify "refs/tags/$Tag" | Out-Null
if ($LASTEXITCODE -ne 0) { git -C $root fetch --quiet origin "refs/tags/${Tag}:refs/tags/$Tag" }
if ($LASTEXITCODE -ne 0) { throw "Tag $Tag not found" }

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("platform-core-build-" + [guid]::NewGuid())
try {
    git -C $root worktree add --quiet --detach (Join-Path $work 'src') $Tag
    if ($LASTEXITCODE -ne 0) { throw "Could not check out $Tag" }
    Push-Location (Join-Path $work 'src')
    try {
        mvn -B install -DskipTests "-Dmaven.repo.local=$repo"
        if ($LASTEXITCODE -ne 0) { throw "The build of $Tag failed" }
    } finally {
        Pop-Location
    }
    Write-Output "Built $Tag into $repo"
} finally {
    git -C $root worktree remove --force (Join-Path $work 'src') 2>$null | Out-Null
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
