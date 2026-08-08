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

# 1. Resolve entry class package path from MANIFEST Module-Entry
$manifestPath = Join-Path $srcAbs "MANIFEST.MF"
$manifestLines = Get-Content $manifestPath
$entryLine = $manifestLines | Where-Object { $_ -match "^Module-Entry:" }
if (-not $entryLine) { Write-Error "MANIFEST.MF missing Module-Entry"; exit 1 }
$entryClass = ($entryLine -split ":", 2)[1].Trim()
$lastDot = $entryClass.LastIndexOf(".")
$pkgPath = $entryClass.Substring(0, $lastDot).Replace(".", "/")
$className = $entryClass.Substring($lastDot + 1)
$topPkg = $entryClass.Split(".")[0]

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
    if ($_.Name -ne "entities") {
        Copy-Item $_.FullName (Join-Path $temp $_.Name) -Recurse -Force
    }
}
$entryClasses = Join-Path $classes $pkgPath
if (Test-Path $entryClasses) {
    Copy-Item (Join-Path $entryClasses "*.class") (Join-Path $temp $pkgPath)
} else {
    Write-Warning "Entry classes not found: $entryClasses (run gradlew compileJava first)"
}

# 4. Create jar (MANIFEST as manifest input -> META-INF/)
$outJarAbs = Join-Path $root $OutJar
Push-Location $temp
try {
    # Collect top-level items: entry package + entities + all resource dirs (skip META-INF, handled by manifest input)
    $topItems = @($topPkg, "entities")
    Get-ChildItem $temp -Directory | ForEach-Object {
        if ($_.Name -ne "META-INF" -and $_.Name -ne "entities" -and $_.Name -ne $topPkg) {
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
