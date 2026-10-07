param(
    [string]$ProjectRoot = "E:\AtomosRemoteWithBackend",
    [switch]$StartSimulator
)

$ErrorActionPreference = "Stop"
Set-ExecutionPolicy -Scope Process Bypass -Force

$Port = 8787
$BackendRoot = Join-Path $ProjectRoot "backend"
$NativeRoot = Join-Path $ProjectRoot "native-android"
$PackageName = "com.atomosremote.nativeapp.backend"
$LogRoot = Join-Path $ProjectRoot "local-test-logs"
New-Item -ItemType Directory -Force -Path $LogRoot | Out-Null
$Stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackendLog = Join-Path $LogRoot "backend_$Stamp.log"
$AndroidLog = Join-Path $LogRoot "android_$Stamp.log"

Write-Host ""
Write-Host "===================================================="
Write-Host " ATOMOS REMOTE BACKEND - LOCAL HARDWARE RUNNER"
Write-Host "===================================================="
Write-Host "Project       : $ProjectRoot"
Write-Host "ApplicationId : $PackageName"
Write-Host "Backend port  : $Port"
Write-Host ""

if (!(Test-Path $BackendRoot)) { throw "Backend not found: $BackendRoot" }
if (!(Test-Path $NativeRoot)) { throw "Native Android not found: $NativeRoot" }

Write-Host "==> Backend dependencies/typecheck/build"
Push-Location $BackendRoot
if (!(Test-Path ".\node_modules")) { npm install }
npm run typecheck
if ($LASTEXITCODE -ne 0) { throw "Backend typecheck failed" }
npm run build
if ($LASTEXITCODE -ne 0) { throw "Backend build failed" }
Pop-Location

$healthOk = $false
try {
    $h = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 2
    $healthOk = [bool]$h.ok
} catch {}

if (!$healthOk) {
    Write-Host "==> Starting backend in separate terminal"
    $cmd = "Set-ExecutionPolicy -Scope Process Bypass -Force; Set-Location '$BackendRoot'; npm run dev 2>&1 | Tee-Object -FilePath '$BackendLog'"
    Start-Process powershell.exe -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $cmd | Out-Null

    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 750
        try {
            $h = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 2
            $healthOk = [bool]$h.ok
        } catch {}
    } while (!$healthOk -and (Get-Date) -lt $deadline)

    if (!$healthOk) { throw "Backend failed to become healthy on port $Port" }
} else {
    Write-Host "PASS: backend already running"
}

if ($StartSimulator) {
    Write-Host "==> Starting optional ag2-sim agent"
    $SimScript = Join-Path $ProjectRoot "START-AGENT-SIMULATOR.ps1"
    if (Test-Path $SimScript) {
        $cmd = "Set-ExecutionPolicy -Scope Process Bypass -Force; Set-Location '$ProjectRoot'; & '$SimScript' -ProjectRoot '$ProjectRoot'"
        Start-Process powershell.exe -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $cmd | Out-Null
    } else {
        $cmd = "Set-ExecutionPolicy -Scope Process Bypass -Force; Set-Location '$BackendRoot'; npm run simulator"
        Start-Process powershell.exe -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $cmd | Out-Null
    }
}

Write-Host "==> Checking ADB"
$adbLines = adb devices
$deviceLines = $adbLines | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (!$deviceLines) { throw "No ADB Android device connected" }
$adbLines | Out-Host

Write-Host "==> adb reverse tcp:$Port -> tcp:$Port"
adb reverse tcp:$Port tcp:$Port | Out-Host

Write-Host "==> Building backend-enabled APK"
Push-Location $NativeRoot
& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Gradle assembleDebug failed" }
Pop-Location

$apk = Get-ChildItem (Join-Path $NativeRoot "app\build\outputs\apk\debug\*.apk") |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (!$apk) { throw "Debug APK not found" }
Write-Host "PASS APK: $($apk.FullName)"

Write-Host "==> Installing separate backend app"
adb install -r $apk.FullName | Out-Host
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "==> Clearing logcat and launching backend app"
adb logcat -c
adb shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Host

Write-Host "==> Opening live filtered log capture"
$logCmd = "adb logcat -v time | Select-String 'AtomRemoteBackend|AtomRemoteGatt|AtomRemotePair|BackendAgent' | Tee-Object -FilePath '$AndroidLog'"
Start-Process powershell.exe -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $logCmd | Out-Null

Write-Host "==> Waiting for ag2-main registration"
$registered = $false
for ($i=0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    try {
        $devices = Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/devices" -TimeoutSec 2
        if ($devices.devices.deviceId -contains "ag2-main") {
            $registered = $true
            break
        }
    } catch {}
}

Write-Host ""
Write-Host "===================================================="
if ($registered) {
    Write-Host "PASS: backend APK agent is WIRED and REGISTERED as ag2-main"
} else {
    Write-Host "FAIL: ag2-main did not register within 30 seconds"
    Write-Host "Check the opened log window and: $AndroidLog"
}
Write-Host "APK         : $($apk.FullName)"
Write-Host "Package     : $PackageName"
Write-Host "Backend log : $BackendLog"
Write-Host "Android log : $AndroidLog"
Write-Host "===================================================="
Write-Host ""
Write-Host "Next: pair AG2 normally in ATOMOS Remote Backend, wait for PacketLink READY, then run:"
Write-Host "  .\TEST-LOCAL-ALL-TABS.ps1"
Write-Host ""
Write-Host "Optional simulator mode next time:"
Write-Host "  .\RUN-BACKEND-HARDWARE-LOCAL.ps1 -StartSimulator"
