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
if ($pkgPath) { New-Item -ItemType Directory -Force -Path (Join-Path $temp $pkgPath) | Out-Null }

# 3. Copy MANIFEST + resources + entry classes
Copy-Item $manifestPath (Join-Path $temp "META-INF/MANIFEST.MF")
$srcEntities = Join-Path $srcAbs "entities"
if (Test-Path $srcEntities) {
    Copy-Item (Join-Path $srcEntities "*") (Join-Path $temp "entities/")
}
# 与 contentpack.gradle 的 from(projectDir) 排除表保持一致：.gradle / build / runs / bin / out
# 都是构建产物——在包目录里跑过 userdev 后 .gradle 会带上数百 MB 的 MC 缓存，漏排会打出 140 MB 的包。
$junkDirs = @(".gradle", "build", "runs", "bin", "out", "entities", "java")
Get-ChildItem $srcAbs -Directory | ForEach-Object {
    if ($junkDirs -notcontains $_.Name) {
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
    # 附加 Module-Lib-Requires 声明的外部库 jar（来自 contentpacks-source/_libs/<库名>/）
    # 库 jar 在编译期与运行期都可见：contentpack 代码可 import 库的 API（如 snownee.jade.*）
    $libLine = $manifestLines | Where-Object { $_ -match "^Module-Lib-Requires:" }
    if ($libLine) {
        $libNames = (($libLine -split ":", 2)[1].Trim() -split ",")
        foreach ($libName in $libNames) {
            $libName = $libName.Trim()
            if (-not $libName) { continue }
            $libJars = Get-ChildItem (Join-Path $srcAbs "_libs/$libName") -Filter "*.jar" -ErrorAction SilentlyContinue
            if (-not $libJars) {
                # 也尝试在 contentpacks-source/_libs/ 全局找
                $libJars = Get-ChildItem (Join-Path $root "contentpacks-source/_libs/$libName") -Filter "*.jar" -ErrorAction SilentlyContinue
            }
            if ($libJars) {
                $cp += ";" + $libJars[0].FullName
                Write-Output "  [lib-cp] $libName -> $($libJars[0].Name)"
            } else {
                Write-Warning "  [lib-cp] library '$libName' not found (javac may fail if code imports it)"
            }
        }
    }
    $classesOut = Join-Path $temp "classes-out"
    New-Item -ItemType Directory -Force -Path $classesOut | Out-Null
    $javaFiles = @(Get-ChildItem $srcJava -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
    if ($javaFiles.Count -eq 0) { Write-Error "No .java files in $srcJava"; exit 1 }
    & javac -encoding UTF-8 -cp $cp -d $classesOut $javaFiles
    if ($LASTEXITCODE -ne 0) { Write-Error "javac compile failed"; exit 1 }
    # 整树复制 javac 输出：$classesOut 是本包专用的临时目录，可整树搬。
    # 若只复制入口包那一层，子包（goals/ handlers/ ai/ combat/ ...）会被【静默丢弃】，
    # 表现为运行期 NoClassDefFoundError，而打包成功、jar tf 只见入口类、零警告。（BUG-1）
    # 整树复制同时让「多顶层包」的包也能正常打包。
    Copy-Item (Join-Path $classesOut '*') $temp -Recurse -Force
    if ($pkgPath -and -not (Test-Path (Join-Path $temp "$pkgPath/$className.class"))) {
        Write-Warning "Entry class not found after copy: $pkgPath/$className.class"
    }
    Write-Output "Compiled pack Java independently ($($javaFiles.Count) files)"
} elseif ($entryClasses -and (Test-Path $entryClasses)) {
    # Fallback: collect from main mod build output (legacy mode).
    # 这里【不能】整树复制 —— $classes 是整个主 mod 的输出，整树会把它一并打进包里。
    # 改为递归复制入口包，修掉同样的丢子包问题（BUG-1）。
    Copy-Item (Join-Path $entryClasses '*') (Join-Path $temp $pkgPath) -Recurse -Force
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
