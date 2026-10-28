# sync-to-test-server.ps1 - sync a committed branch snapshot to the test server (caracatie)
#
# Usage (from the repo root):
#   .\scripts\sync-to-test-server.ps1
#   .\scripts\sync-to-test-server.ps1 -Branch <name> -Server <ssh-alias> [-RemoteRoot <path>]
#
# Principles:
#   - Only COMMITTED refs are synced (git bundle never carries uncommitted
#     working-tree changes = snapshot principle; local WIP must not leak into
#     the test environment).
#   - The server checkout is disposable: reset --hard + clean -fdx, replayable anytime.
#   - Server GRADLE_USER_HOME convention: $RemoteRoot/gradle-home (warmed from the
#     Windows D:\.gradle cache, because maven.neoforged.net is unreachable from the
#     server). See design/RESEARCH_2026-10-26_*.md section 6, E1 results.
#
# After syncing, run the GameTest on the server (separate shell):
#   cd $RemoteRoot/checkout
#   export GRADLE_USER_HOME=$RemoteRoot/gradle-home
#   export JAVA_HOME=$RemoteRoot/jdk-21
#   export JAVA_TOOL_OPTIONS=-Xmx1024m
#   ./gradlew -Porg.gradle.java.installations.paths=$RemoteRoot/jdk-21 \
#             -Dorg.gradle.jvmargs=-Xmx1536m runGameTestServer --console=plain
#   (exit code 0 = all tests passed)
#
# Note: the remote command string deliberately contains NO double quotes -
# OpenSSH-for-Windows drops them when concatenating remote-command arguments
# (empirically observed 2026-10-28). Keep it that way.
#
# NOTE: keep this file ASCII-only (no non-ASCII characters): Windows PowerShell
# 5.1 reads BOM-less .ps1 as the system codepage (GBK on zh-CN) and mangles it.
#
# Origin: codified from the manually tested E1 procedure, 2026-10-28
# (design/RESEARCH_2026-10-26_*.md section 6).

param(
    [string]$Branch = "Neoforge-1.21.1",
    [string]$Server = "caracatie",
    [string]$RemoteRoot = "/mnt/HDD_ST4000VX015-3CU1/mc-test"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    $localHead = (git rev-parse --short HEAD).Trim()
    $localBranch = (git rev-parse --abbrev-ref HEAD).Trim()
    Write-Host "[sync] local: $localBranch @ $localHead" -ForegroundColor DarkGray

    $bundleName = "mc-test-" + $Branch + ".bundle"
    $bundle = Join-Path $env:TEMP $bundleName
    if (Test-Path $bundle) { Remove-Item $bundle -Force }
    git bundle create $bundle $Branch
    if ($LASTEXITCODE -ne 0) { throw "git bundle create failed" }
    $mb = [math]::Round((Get-Item $bundle).Length / 1MB, 1)
    Write-Host "[sync] bundle: $mb MB (branch $Branch)"

    $remoteBundle = "$RemoteRoot/sync/$bundleName"
    scp $bundle "$($Server):$remoteBundle"
    if ($LASTEXITCODE -ne 0) { throw "scp failed" }

    $remote = "cd $RemoteRoot/checkout && git fetch $remoteBundle $Branch && git reset --hard FETCH_HEAD && git clean -fdx && chmod +x gradlew && git log -1 --format=%h"
    # git reset prints "HEAD is now at ..." - the short hash from git log is the LAST line
    $remoteHead = ((ssh $Server $remote) | Where-Object { $_ -ne "" } | Select-Object -Last 1).Trim()
    Write-Host "[sync] server HEAD: $remoteHead" -ForegroundColor Green
    if ($localHead -ne $remoteHead) {
        Write-Warning "[sync] local $localHead != server $remoteHead - check bundle/branch name"
    }
}
finally {
    Pop-Location
}
