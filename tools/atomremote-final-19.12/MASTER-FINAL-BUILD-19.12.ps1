param(
    [string]$ProjectRoot = "E:\AtomosRemote",
    [switch]$Install,
    [string]$Device = ""
)
$ErrorActionPreference = "Stop"
$native = Join-Path $ProjectRoot "native-android"
$tools = Join-Path $ProjectRoot "tools\atomremote-final-19.12"
$verify = Join-Path $tools "MASTER-VERIFY-SOURCE-19.12.ps1"
if (!(Test-Path $native)) { throw "Native project not found: $native" }
if (!(Test-Path $verify)) { throw "Final verifier not found: $verify" }

Write-Host "=== AtomRemote 19.12 FINAL CLEAN BUILD ===" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"

& $verify -ProjectRoot $ProjectRoot
if ($LASTEXITCODE -ne 0) { throw "Source verification failed." }

$locked = @(
 "app\src\main\java\com\atomosremote\nativeapp\ble\TcsPacketLinkTransport.kt",
 "app\src\main\java\com\atomosremote\nativeapp\ble\TcsConnectedDataTransfer.kt",
 "app\src\main\java\com\atomosremote\nativeapp\ble\BleModels.kt"
)
$lockedHashes = @{}
foreach ($r in $locked) {
    $p = Join-Path $native $r
    if (Test-Path $p) { $lockedHashes[$r] = (Get-FileHash $p -Algorithm SHA256).Hash }
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$reportDir = Join-Path $ProjectRoot "final-test\19.12-$stamp"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

Push-Location $native
try {
    Write-Host "Running clean Gradle build..." -ForegroundColor Yellow
    & .\gradlew.bat clean assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle clean assembleDebug failed with exit code $LASTEXITCODE" }
}
finally { Pop-Location }

$apkCandidates = Get-ChildItem -Path (Join-Path $native "app\build\outputs\apk\debug") -Filter "*.apk" -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
if (!$apkCandidates -or $apkCandidates.Count -eq 0) { throw "Debug APK not found after successful Gradle build." }
$apk = $apkCandidates[0].FullName
$apkHash = (Get-FileHash $apk -Algorithm SHA256).Hash
Copy-Item -Force $apk (Join-Path $reportDir (Split-Path $apk -Leaf))
Set-Content -Path (Join-Path $reportDir "APK-SHA256.txt") -Value "$apkHash  $(Split-Path $apk -Leaf)" -Encoding ASCII

foreach ($r in $lockedHashes.Keys) {
    $now = (Get-FileHash (Join-Path $native $r) -Algorithm SHA256).Hash
    if ($now -ne $lockedHashes[$r]) { throw "Frozen core changed during build: $r" }
}
Write-Host "PASS: frozen PacketLink/pairing core unchanged through build." -ForegroundColor Green
Write-Host "APK: $apk" -ForegroundColor Green
Write-Host "APK SHA256: $apkHash"

if ($Install) {
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (!(Test-Path $adb)) { $adb = "adb" }
    if ($Device) {
        & $adb connect $Device | Out-Host
        & $adb -s $Device install -r $apk
    } else {
        & $adb install -r $apk
    }
    if ($LASTEXITCODE -ne 0) { throw "ADB install failed with exit code $LASTEXITCODE" }
    Write-Host "APK installed." -ForegroundColor Green
}

Write-Host "FINAL CLEAN BUILD COMPLETE" -ForegroundColor Cyan
Write-Host "Artifacts: $reportDir"
