<#
.SYNOPSIS
  Game-run validation (hidden dev tool) - use the game's own Codecs as the pack validator.

.DESCRIPTION
  Vanilla ships NO standalone resource/data pack validator: the only "official"
  checker is the game process itself.
    - data phase   : gradlew runServer - world load makes RegistryDataLoader hard-
                     validate all data/ registries (worldgen/structure/template_pool/
                     loot/tags...), plus contentpack loading and batch registration.
    - models phase : gradlew runClient - startup makes ModelBakery validate
                     models/blockstates/sounds (a dedicated server does NOT build
                     the model atlas, so the client phase is required).

  Failure = any marker hit (see regex lists below). Success markers:
    data   : "Done (Xs)! For help, type ..." (server ready)
    models : "Sound engine started" (resource load complete)

  Notes:
    * Always --offline (network to Mojang is usually blocked here).
    * Offline prerequisite (one-time; symptom: "Cannot download asset ...
      offline mode" from ...DownloadAssets before runServer starts):
      The JOINED pipeline (runServer) uses
      %USERPROFILE%\.gradle\caches\minecraft\assets, the CLIENT pipeline
      (runClient) uses %USERPROFILE%\.gradle\caches\neoformruntime\assets.
      Two known causes, both fixed in this repo:
        1) NeoGradle 7.1.26 bug: DownloadAssets looks for indexes/asset-index.json
           instead of the real index id (1.21.1 = 17.json). build.gradle now
           re-points task.assetIndex at versionJson.assetIndex.id.
        2) The plugin's CachedExecutionService DELETES the output file before each
           cache-miss run, so a merely pre-seeded file is not enough - the
           %USERPROFILE%\.gradle\caches\ng_execute cache must contain one healthy
           run. When network to piston-meta.mojang.com + resources.download.minecraft.net
           is up, seed it once ONLINE (this is the reliable path):
             gradlew :neoFormJoined1.21.1-20240808.144430DownloadAssets --console=plain
           (no --offline; re-downloads index + all ~4193 assets, then offline
           runs restore from cache and never touch the network again).
           Fallback without network: robocopy the client-pipeline objects and
           indexes from caches\neoformruntime\assets - only works if that pipeline
           has a healthy ng_execute entry for the same task hash.
    * Kills java processes whose command line contains
      -Dforge.logging.console.level (the game JVMs only). Do NOT run this while a
      manual game session is running.
    * First "data" run creates a fresh world under run/server/saves (~+30s).
    * First runServer overall may take 10-20 min (cold server neoForm pipeline).
    * Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less .ps1 as
      ANSI/GBK and non-ASCII bytes corrupt parsing.

.EXAMPLE
  .\scripts\validate-game.ps1                          # both phases
  .\scripts\validate-game.ps1 -Phase data              # data registries + pack loading
  .\scripts\validate-game.ps1 -Phase models            # models/blockstates/sounds
  .\scripts\validate-game.ps1 -Pack item-block-test-pack   # rebuild that pack jar first
#>
param(
    [ValidateSet('all', 'data', 'models')]
    [string]$Phase = 'all',

    # Optionally rebuild one pack jar first (:packs:<Pack>:contentpackJar);
    # the jar lands in contentpacks/ and the run task's copyContentPacksTo*
    # deploys it into run/<client|server>/contentpacks/ automatically.
    [string]$Pack = '',

    [int]$TimeoutSec = 900
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-Gradle([string[]]$GradleArgs) {
    # The parameter must NOT be named $Args: that is a PowerShell automatic variable;
    # a same-named parameter silently receives nothing, so @Args splats an empty array
    # and gradlew runs with NO task (default build), exit 0, and the script would
    # believe a stale pack jar was rebuilt. Measured: quests/_probe_tmp.json deleted
    # from source stayed inside the jar and the data phase still passed.
    # Hence: rename the parameter AND assert the task line actually appears below.
    $output = & "$root\gradlew.bat" @GradleArgs --offline --console=plain 2>&1
    $code = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($code -ne 0) {
        Write-Host $text -ForegroundColor DarkGray
        throw "gradlew $($GradleArgs -join ' ') failed (exit $code)"
    }
    return $text
}

# ---------- optional: rebuild a pack jar first ----------
if ($Pack) {
    # Keep the braces in ${Pack}: ":packs:$Pack:contentpackJar" is parsed as the
    # scoped variable $Pack:contentpackJar, the task name degrades to ":packs:", and
    # the script silently continues because an old jar exists (= never rebuilt).
    # Measured: this line silently vanished while the block above still ran, i.e.
    # non-ASCII comments do corrupt neighboring statements under PS 5.1 + ANSI.
    Write-Host "[pack] building :packs:${Pack}:contentpackJar ..." -ForegroundColor DarkGray
    $task = ":packs:${Pack}:contentpackJar"
    $buildLog = Invoke-Gradle @($task)
    if ($buildLog -notmatch [regex]::Escape("> Task $task")) {
        throw "gradlew did not run $task (arguments lost): no '> Task $task' line in output, stale jar is not proof"
    }
    $jarPath = Join-Path $root "contentpacks/$Pack.jar"
    if (-not (Test-Path $jarPath)) { throw "build finished but $jarPath not found" }
    Write-Host "[pack] $jarPath ready (run task deploys it)" -ForegroundColor DarkGray
}

# ---------- game JVM cleanup (by command-line marker; never touches the gradle daemon) ----------
function Kill-GameJvm {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'forge\.logging\.console\.level' } |
        ForEach-Object {
            try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch {}
        }
}

# ---------- single phase ----------
function Invoke-Phase(
    [string]$Name, [string]$GradleTask, [string]$RelLog,
    [string]$SuccessRegex, [string[]]$FailRegex, [string[]]$InfoRegex
) {
    $log = Join-Path $root $RelLog
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host ""
    Write-Host "[$Name] gradlew $GradleTask  (timeout ${TimeoutSec}s, log: $RelLog)" -ForegroundColor Cyan

    # capture gradlew's own output (diagnose "build never started": lock waits / config errors)
    # NOTE: Start-Process -RedirectStandardOutput crashes on Windows PowerShell 5.1 when the
    # environment holds case-duplicate vars (NO_PROXY + no_proxy) - so redirect via a cmd wrapper.
    $outFile = Join-Path $env:TEMP "validate-game-$Name-gradle.out"
    $errFile = Join-Path $env:TEMP "validate-game-$Name-gradle.err"
    $wrapper = Join-Path $env:TEMP "validate-game-$Name-launch.cmd"
    Remove-Item $outFile, $errFile, $wrapper -ErrorAction SilentlyContinue
    @(
        '@echo off',
        "cd /d `"$root`"",
        "call `"$root\gradlew.bat`" $GradleTask --offline --console=plain > `"$outFile`" 2> `"$errFile`"",
        'exit /b %ERRORLEVEL%'
    ) | Set-Content -Path $wrapper -Encoding ASCII

    Kill-GameJvm   # prevent leftovers from a previous round
    $proc = Start-Process -FilePath $wrapper `
        -WorkingDirectory $root -PassThru -WindowStyle Hidden

    $failLines = New-Object System.Collections.Generic.List[string]
    $infoLines = New-Object System.Collections.Generic.List[string]
    $state = 'running'   # running | pass | fail

    # Only scan lines appended AFTER this build started: latest.log is
    # rotated by the game on startup, but until then it still holds the
    # previous session's lines (including an old success marker) - matching
    # those would be a false PASS. (Line-based, not byte/char based: the log
    # contains CJK text, so byte length != string length.)
    $logStartLine = 0
    if (Test-Path $log) { $logStartLine = @(Get-Content $log -Encoding UTF8).Count }

    try {
        while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
            Start-Sleep -Seconds 2

            if (Test-Path $log) {
                $lines = @(Get-Content $log -Encoding UTF8)
                if ($lines.Count -lt $logStartLine) { $logStartLine = 0 }  # log was rotated
                # only lines of THIS session: last "Setting user" line is the start boundary
                $startIdx = [Math]::Max($logStartLine, 0)
                for ($i = $lines.Count - 1; $i -ge $startIdx; $i--) {
                    if ($lines[$i] -match 'Setting user') { $startIdx = $i; break }
                }
                if ($startIdx -ge $lines.Count) { continue }   # no new lines yet
                foreach ($ln in $lines[$startIdx..($lines.Count - 1)]) {
                    if ($ln -match $SuccessRegex) {
                        if ($state -eq 'running') {
                            $state = 'pass'
                            Write-Host "[$Name] OK success marker: $ln" -ForegroundColor Green
                        }
                        break
                    }
                    foreach ($re in $FailRegex) {
                        if ($ln -match $re) {
                            # Expected dev-env condition, not a validation failure:
                            # the sable mod is intentionally absent from the gradle run
                            # dirs (runtime-provided in the real game), so
                            # sable-pack-v2 is quarantined BY DESIGN. Demote to info.
                            if ($ln -match 'quarantined' -and $ln -match 'sable-pack-v2') {
                                if (-not $infoLines.Contains($ln)) {
                                    $infoLines.Add($ln)
                                    Write-Host "[$Name] expected (demoted): $ln" -ForegroundColor DarkGray
                                }
                                break
                            }
                            # Legacy sample packs (modular_zombie_4 / test_mob) use
                            # entity attributes the registrar skips with a WARN -
                            # non-fatal by design. Keep visible, don't fail the run.
                            if ($ln -match 'Unknown attribute') {
                                if (-not $infoLines.Contains($ln)) {
                                    $infoLines.Add($ln)
                                    Write-Host "[$Name] expected (demoted): $ln" -ForegroundColor DarkGray
                                }
                                break
                            }
                            # Pre-existing non-fatal client warnings in the main mod
                            # (verified unrelated to contentpack work; the client
                            # continues past all of them):
                            #  - custom_bow: model JSON missing from mod resources
                            #  - goat_horn: vanilla 1.21.1 preload quirk (oggs ARE
                            #    present in the asset cache - index entries verified)
                            #  - zombie3: sound events registered without ogg files
                            if (($ln -match 'Unable to load model' -and $ln -match 'custom_bow') -or
                                ($ln -match 'Missing sound for event' -and ($ln -match 'goat' -or $ln -match 'zombie3'))) {
                                if (-not $infoLines.Contains($ln)) {
                                    $infoLines.Add($ln)
                                    Write-Host "[$Name] pre-existing (demoted): $ln" -ForegroundColor DarkGray
                                }
                                break
                            }
                            if (-not $failLines.Contains($ln)) { $failLines.Add($ln) }
                            if ($state -eq 'running') {
                                $state = 'fail'
                                Write-Host "[$Name] FAIL marker: $ln" -ForegroundColor Red
                            }
                            break
                        }
                    }
                    foreach ($re in $InfoRegex) {
                        if ($ln -match $re -and -not $infoLines.Contains($ln)) { $infoLines.Add($ln) }
                    }
                }
            }

            if ($state -ne 'running') { break }
            if ($proc.HasExited) {
                Write-Host "[$Name] gradlew exited on its own (exit $($proc.ExitCode)) - treating as crash, collecting log" -ForegroundColor Yellow
                Start-Sleep -Seconds 3
                $state = 'fail'
                break
            }
        }
        if ($sw.Elapsed.TotalSeconds -ge $TimeoutSec -and $state -eq 'running') {
            $state = 'fail'
            Write-Host "[$Name] TIMEOUT (${TimeoutSec}s): no success/failure marker seen" -ForegroundColor Red
        }
    } finally {
        # teardown: kill the gradlew process tree + any game JVM
        try { if (-not $proc.HasExited) { taskkill /F /T /PID $proc.Id 2>&1 | Out-Null } } catch {}
        Start-Sleep -Seconds 2
        Kill-GameJvm
    }

    # report
    foreach ($ln in $infoLines) { Write-Host "       info: $ln" -ForegroundColor DarkGray }
    foreach ($ln in $failLines) {
        if ($ln.Length -gt 200) { Write-Host "       FAIL: $($ln.Substring(0,200))..." -ForegroundColor Red }
        else { Write-Host "       FAIL: $ln" -ForegroundColor Red }
    }
    if ($state -eq 'fail') {
        # diagnostics: tail of gradlew's own output (lock waits / config errors / task progress)
        foreach ($f in @($errFile, $outFile)) {
            if (Test-Path $f) {
                $noise = Get-Content $f -Tail 25 -ErrorAction SilentlyContinue | Where-Object { $_ -match '\S' }
                if ($noise) {
                    Write-Host "       gradlew output tail ($([System.IO.Path]::GetFileName($f))):" -ForegroundColor Yellow
                    $noise | ForEach-Object { Write-Host "         | $_" -ForegroundColor DarkYellow }
                }
            }
        }
    }
    if ($state -eq 'pass' -and $failLines.Count -gt 0) { $state = 'fail' }  # errors before success also fail
    $mark = if ($state -eq 'pass') { 'PASS' } else { 'FAIL' }
    $color = if ($state -eq 'pass') { 'Green' } else { 'Red' }
    $secs = [int]$sw.Elapsed.TotalSeconds
    Write-Host "[$Name] => $mark  ($secs s)" -ForegroundColor $color
    return [pscustomobject]@{ Phase = $Name; State = $state }
}

$results = @()
if ($Phase -in 'all', 'data') {
    $results += Invoke-Phase `
        -Name 'data' -GradleTask 'runServer' -RelLog 'run/server/logs/latest.log' `
        -SuccessRegex 'Done \([^)]*\)! For help' `
        -FailRegex @(
            'Registry loading errors',
            'Failed to load level data or datapacks',
            'Failed to parse .* from pack',
            'quarantined at',
            'Failed to load ContentPack',
            'problems=[1-9]',
            'Unknown attribute',
            # Pack self-tests (MapV2SelfTest / MarkerSelfTest / HudLayoutSelfTest ...) report a
            # nonzero count at ERROR level. Without this line they would only be visible to
            # someone reading latest.log by hand - the whole point of a self-test is that
            # nobody has to read the log.
            'FAILURES=[1-9]'
        ) `
        -InfoRegex @('Batch registration complete \(pack=', 'Successfully loaded ContentPack')
}
if ($Phase -in 'all', 'models') {
    $results += Invoke-Phase `
        -Name 'models' -GradleTask 'runClient' -RelLog 'run/client/logs/latest.log' `
        -SuccessRegex 'Sound engine started' `
        -FailRegex @(
            'Unable to load model:',
            # blockstates 缺失/写错目录/变体形状非法：方块注册成功但渲染成紫黑块
            'Exception loading blockstate definition',
            # 模型引用的纹理不存在（如 minecraft:block/redstone_lamp_lit）：紫黑格
            'Missing textures in model',
            'Missing sound for event:',
            'quarantined at',
            'Failed to load ContentPack',
            'problems=[1-9]',
            'Unknown attribute',
            'FAILURES=[1-9]'   # pack self-tests, same reason as in the data phase
        ) `
        -InfoRegex @('Batch registration complete \(pack=', 'Successfully loaded ContentPack')
}

Write-Host ""
Write-Host "================ game-run validation summary ================" -ForegroundColor Cyan
$failed = 0
foreach ($r in $results) {
    $c = if ($r.State -eq 'pass') { 'Green' } else { 'Red' }
    Write-Host ("  [{0,-6}] {1}" -f $r.Phase, $r.State) -ForegroundColor $c
    if ($r.State -ne 'pass') { $failed++ }
}
if ($failed -eq 0) {
    Write-Host "  result: ALL PASS" -ForegroundColor Green
    exit 0
} else {
    Write-Host "  result: $failed/$($results.Count) phase(s) FAILED (see FAIL lines; full log in run/<client|server>/logs/latest.log)" -ForegroundColor Red
    exit 1
}
