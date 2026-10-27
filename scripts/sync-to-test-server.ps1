# sync-to-test-server.ps1 —— 把已提交的分支快照同步到测试服务器（caracatie，HDD checkout）
#
# 用法（仓库根目录下）：
#   .\scripts\sync-to-test-server.ps1                      # 默认 Neoforge-1.21.1 → caracatie
#   .\scripts\sync-to-test-server.ps1 -Branch <名> -Server <ssh别名> [-RemoteRoot <路径>]
#
# 原则：
#   - 只同步**已提交**的 ref（git bundle 天然不含工作区未提交改动 = 快照原则，
#     避免把本地 WIP 脏状态带进测试环境）。
#   - 服务器 checkout = 一次性快照目录：reset --hard + clean -fdx 后可随时重放。
#   - 服务器 GRADLE_USER_HOME 约定 = $RemoteRoot/gradle-home（已用 Windows 缓存预热，
#     因 maven.neoforged.net 从服务器不可达；预热方法见 design/RESEARCH_2026-10-26_模组内容包测试验收系统.md §6 E1 结果）。
#
# 同步后在服务器上跑 GameTest（另开 shell）：
#   cd $RemoteRoot/checkout
#   export GRADLE_USER_HOME=$RemoteRoot/gradle-home
#   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
#   export JAVA_TOOL_OPTIONS=-Xmx1024m
#   ./gradlew -Porg.gradle.java.installations.paths=/usr/lib/jvm/java-21-openjdk-amd64 \
#             -Dorg.gradle.jvmargs=-Xmx1536m runGameTestServer --console=plain
#   （退出码 0 = 全部测试通过；见 E1 结果节。）
#
# 来源：2026-10-28 E1 实测配方固化（design/RESEARCH_2026-10-26_模组内容包测试验收系统.md §6）。

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
    Write-Host "[sync] 本地: $localBranch @ $localHead" -ForegroundColor DarkGray

    $bundle = Join-Path $env:TEMP "mc-test-$Branch.bundle"
    if (Test-Path $bundle) { Remove-Item $bundle -Force }
    git bundle create $bundle $Branch
    if ($LASTEXITCODE -ne 0) { throw "git bundle create 失败" }
    $mb = [math]::Round((Get-Item $bundle).Length / 1MB, 1)
    Write-Host "[sync] bundle: $mb MB (分支 $Branch)"

    $remoteBundle = "$RemoteRoot/sync/mc-test-$Branch.bundle"
    scp $bundle "$Server:$remoteBundle"
    if ($LASTEXITCODE -ne 0) { throw "scp 失败" }

    # 远端命令刻意不含双引号（OpenSSH-for-Windows 参数拼接会丢引号，2026-10-28 实测）；
    # git log --format 不带空格。
    $remote = "cd $RemoteRoot/checkout && git fetch $remoteBundle $Branch && git reset --hard FETCH_HEAD && git clean -fdx && chmod +x gradlew && git log -1 --format=%h"
    $remoteHead = (ssh $Server $remote) -join " "
    Write-Host "[sync] 服务器 HEAD: $remoteHead" -ForegroundColor Green
    if ($localHead -ne $remoteHead) {
        Write-Warning "[sync] 本地 $localHead 与服务器 $remoteHead 不一致！检查 bundle/分支名。"
    }
}
finally {
    Pop-Location
}
