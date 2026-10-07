param(
    [string]$ProjectRoot = "E:\AtomosRemoteWithBackend",
    [string]$AdbSerial = "192.168.100.4:40731",
    [int]$Port = 8787,
    [switch]$StartSimulator
)

$ErrorActionPreference = "Stop"
Set-ExecutionPolicy -Scope Process Bypass -Force

$BackendRoot = Join-Path $ProjectRoot "backend"
$NativeRoot = Join-Path $ProjectRoot "native-android"
$Package = "com.atomosremote.nativeapp.backend"
$LogRoot = Join-Path $ProjectRoot "local-test-logs"
New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$androidLog = Join-Path $LogRoot "android_wireless_$stamp.log"

Write-Host ""
Write-Host "===================================================="
Write-Host " ATOMOS REMOTE BACKEND - WIRELESS LOCAL RUNNER"
Write-Host "===================================================="
Write-Host "Project : $ProjectRoot"
Write-Host "ADB     : $AdbSerial"
Write-Host "Package : $Package"
Write-Host "Port    : $Port"

Write-Host "`n==> Connecting wireless ADB"
adb connect $AdbSerial | Out-Host
$deviceLine = adb devices | Select-String ([regex]::Escape($AdbSerial) + "\s+device")
if (!$deviceLine) { throw "Wireless ADB device is not connected: $AdbSerial" }

Write-Host "`n==> Backend typecheck/build"
Push-Location $BackendRoot
if (!(Test-Path .\node_modules)) { npm install }
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
    Write-Host "`n==> Starting backend"
    Start-Process powershell.exe -ArgumentList @(
        "-NoExit", "-ExecutionPolicy", "Bypass", "-Command",
        "Set-Location '$BackendRoot'; npm run dev"
    )
    $deadline = (Get-Date).AddSeconds(25)
    do {
        Start-Sleep -Milliseconds 750
        try {
            $h = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 2
            $healthOk = [bool]$h.ok
        } catch {}
    } while (!$healthOk -and (Get-Date) -lt $deadline)
    if (!$healthOk) { throw "Backend failed to start on port $Port" }
}
Write-Host "PASS: backend healthy"

if ($StartSimulator) {
    Write-Host "`n==> Starting optional simulator"
    Start-Process powershell.exe -ArgumentList @(
        "-NoExit", "-ExecutionPolicy", "Bypass", "-Command",
        "Set-Location '$BackendRoot'; npm run simulator"
    )
}

Write-Host "`n==> Building Android APK"
Push-Location $NativeRoot
.\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Android build failed" }
Pop-Location

$apk = Get-ChildItem "$NativeRoot\app\build\outputs\apk\debug\*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (!$apk) { throw "Debug APK not found" }
Write-Host "PASS APK: $($apk.FullName)"

Write-Host "`n==> Installing APK to wireless device"
adb -s $AdbSerial install -r $apk.FullName | Out-Host
if ($LASTEXITCODE -ne 0) { throw "APK install failed" }

# install -r may stop the application, so reverse and launch happen AFTER install.
Write-Host "`n==> Re-applying adb reverse after install"
adb -s $AdbSerial reverse --remove tcp:$Port 2>$null | Out-Null
adb -s $AdbSerial reverse tcp:$Port tcp:$Port | Out-Host

Write-Host "`n==> Force-stopping and launching backend app"
adb -s $AdbSerial shell am force-stop $Package | Out-Null
adb -s $AdbSerial logcat -c
adb -s $AdbSerial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Host

Write-Host "`n==> Starting filtered log capture"
$logCommand = @"
adb -s $AdbSerial logcat -v time | Tee-Object -FilePath '$androidLog' | Select-String 'AtomRemoteBackend|AtomRemoteGatt|AtomRemotePair|TCS18|TCS19|AndroidRuntime'
"@
Start-Process powershell.exe -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $logCommand)

Write-Host "`n==> Waiting for ag2-main registration"
$registered = $false
$deadline = (Get-Date).AddSeconds(25)
do {
    Start-Sleep -Seconds 1
    try {
        $d = Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/devices" -TimeoutSec 2
        if ($d.devices | Where-Object { $_.deviceId -eq "ag2-main" }) {
            $registered = $true
            break
        }
    } catch {}
} while ((Get-Date) -lt $deadline)

Write-Host ""
if ($registered) {
    Write-Host "===================================================="
    Write-Host "PASS: ag2-main REGISTERED"
    Write-Host "===================================================="
    Write-Host "APK : $($apk.FullName)"
    Write-Host "Log : $androidLog"
    Write-Host ""
    Write-Host "Now connect/pair AG2 in ATOMOS Remote Backend."
    Write-Host "After PacketLink READY run:"
    Write-Host "  .\DIAGNOSE-BACKEND-AGENT.ps1 -AdbSerial '$AdbSerial'"
} else {
    Write-Host "FAIL: ag2-main did not register"
    Write-Host ""
    Write-Host "Recent backend-agent logs:"
    adb -s $AdbSerial logcat -d -v time | Select-String "AtomRemoteBackend|AndroidRuntime" | Select-Object -Last 80
    throw "Backend Android agent did not register"
}
