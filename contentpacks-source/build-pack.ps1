# build-pack.ps1 - Build a contentpack jar (template script)
# Usage: powershell -ExecutionPolicy Bypass -File contentpacks-source/build-pack.ps1 -SourceDir contentpacks-source/test-mob-pack -OutJar contentpacks/test-mob-pack.jar
# Depends: main mod compiled (gradlew compileJava), JDK jar command on PATH
# Structure: entry class .class from main build output + MANIFEST/resources/JSON from source dir -> jar

param(
    [string]$SourceDir = "contentpacks-source/test-mob-pack",
    [string]$OutJar = "contentpacks/test-mob-pack.jar"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "build/classes/java/main"
$temp = Join-Path $env:TEMP "contentpack-build"
$srcAbs = Join-Path $root $SourceDir

Write-Output "=== Building contentpack jar ==="
Write-Output "Source : $srcAbs"
Write-Output "Output : $(Join-Path $root $OutJar)"

# 1. Resolve entry class package path from MANIFEST Module-Entry (optional: 纯数据包可无入口类)
$manifestPath = Join-Path $srcAbs "MANIFEST.MF"
$manifestLines = Get-Content $manifestPath
$entryLine = $manifestLines | Where-Object { $_ -match "^Module-Entry:" }
$entryClass = $null
$pkgPath = $null
$topPkg = $null
if ($entryLine) {
    $entryClass = ($entryLine -split ":", 2)[1].Trim()
    $lastDot = $entryClass.LastIndexOf(".")
    $pkgPath = $entryClass.Substring(0, $lastDot).Replace(".", "/")
    $className = $entryClass.Substring($lastDot + 1)
    $topPkg = $entryClass.Split(".")[0]
} else {
    Write-Output "Note: MANIFEST has no Module-Entry (pure data pack, no Java code)"
}

# 2. Rebuild temp dir
if (Test-Path $temp) { Remove-Item -Recurse -Force $temp }
New-Item -ItemType Directory -Force -Path (Join-Path $temp "META-INF") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $temp "entities") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $temp $pkgPath) | Out-Null

# 3. Copy MANIFEST + resources + entry classes
Copy-Item $manifestPath (Join-Path $temp "META-INF/MANIFEST.MF")
$srcEntities = Join-Path $srcAbs "entities"
if (Test-Path $srcEntities) {
    Copy-Item (Join-Path $srcEntities "*") (Join-Path $temp "entities/")
}
Get-ChildItem $srcAbs -Directory | ForEach-Object {
    if ($_.Name -ne "entities" -and $_.Name -ne "java") {
        Copy-Item $_.FullName (Join-Path $temp $_.Name) -Recurse -Force
    }
}
# 3b. Compile/collect entry classes: prefer independent compile (pack java/ sources), fallback main mod output
$srcJava = Join-Path $srcAbs "java"
$entryClasses = if ($pkgPath) { Join-Path $classes $pkgPath } else { $null }
if (Test-Path $srcJava) {
    # Independent compile: javac with main mod compile classpath (from writeCompileClasspath)
    $cpFile = Join-Path $root "build/runtime-classpath.txt"
    if (-not (Test-Path $cpFile)) {
        & (Join-Path $root "gradlew.bat") writeCompileClasspath --no-configuration-cache --console=plain | Out-Null
        if (-not (Test-Path $cpFile)) { Write-Error "Failed to generate compile classpath"; exit 1 }
    }
    $mainClasses = Join-Path $root "build/classes/java/main"
    $cp = "$mainClasses;$((Get-Content $cpFile -Raw).Trim())"
    $classesOut = Join-Path $temp "classes-out"
    New-Item -ItemType Directory -Force -Path $classesOut | Out-Null
    $javaFiles = @(Get-ChildItem $srcJava -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
    if ($javaFiles.Count -eq 0) { Write-Error "No .java files in $srcJava"; exit 1 }
    & javac -encoding UTF-8 -cp $cp -d $classesOut $javaFiles
    if ($LASTEXITCODE -ne 0) { Write-Error "javac compile failed"; exit 1 }
    if ($pkgPath) {
        $outPkg = Join-Path $classesOut $pkgPath
        if (Test-Path $outPkg) {
            Copy-Item (Join-Path $outPkg "*.class") (Join-Path $temp $pkgPath)
        } else {
            Write-Warning "Compiled entry package not found: $outPkg"
        }
    }
    Write-Output "Compiled pack Java independently ($($javaFiles.Count) files)"
} elseif ($entryClasses -and (Test-Path $entryClasses)) {
    # Fallback: collect from main mod build output (legacy mode)
    Copy-Item (Join-Path $entryClasses "*.class") (Join-Path $temp $pkgPath)
} elseif ($entryClasses) {
    Write-Warning "Entry classes not found: $entryClasses (run gradlew compileJava first)"
}

# 4. Create jar (MANIFEST as manifest input -> META-INF/)
$outJarAbs = Join-Path $root $OutJar
Push-Location $temp
try {
    # Collect top-level items: entry package + entities + all resource dirs (skip META-INF/classes-out/java, handled separately)
    $topItems = @()
    if ($topPkg) { $topItems += $topPkg }
    $topItems += "entities"
    Get-ChildItem $temp -Directory | ForEach-Object {
        if ($_.Name -ne "META-INF" -and $_.Name -ne "entities" -and $_.Name -ne $topPkg -and $_.Name -ne "classes-out" -and $_.Name -ne "java") {
            $topItems += $_.Name
        }
    }
    & jar cfm $outJarAbs "META-INF/MANIFEST.MF" @topItems
} finally {
    Pop-Location
}

# 5. Verify
Write-Output "=== Jar contents ==="
& jar tf $outJarAbs
Write-Output "=== Done: $(Join-Path $root $OutJar) ==="

# 6. Sync to game run directory (if present)
# Game runs from run/client (build.gradle workingDirectory); ContentPackLoader scans
# relative 'contentpacks' = run/client/contentpacks, NOT project root contentpacks/.
# Auto-copy built jar there so the game loads it.
$runPackDir = Join-Path $root "run/client/contentpacks"
if (Test-Path $runPackDir) {
    $dest = Join-Path $runPackDir (Split-Path -Leaf $outJarAbs)
    Copy-Item $outJarAbs $dest -Force
    Write-Output "=== Synced to run dir: $dest ==="
} else {
    Write-Output "=== Note: run/client/contentpacks not found, skipping run sync ==="
}
