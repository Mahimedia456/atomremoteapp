param(
    [string]$ProjectRoot = "E:\AtomosRemoteWithBackend",
    [int]$Port = 8787
)

$ErrorActionPreference = "Stop"
Set-ExecutionPolicy -Scope Process Bypass -Force

$BackendRoot = Join-Path $ProjectRoot "backend"
$NativeRoot  = Join-Path $ProjectRoot "native-android"

Write-Host ""
Write-Host "============================================="
Write-Host " ATOMREMOTE LOCAL HARDWARE TEST"
Write-Host "============================================="
Write-Host "Project : $ProjectRoot"
Write-Host "Backend : $BackendRoot"
Write-Host "Native  : $NativeRoot"
Write-Host ""

if (!(Test-Path $BackendRoot)) {
    throw "Backend folder not found: $BackendRoot"
}

if (!(Test-Path $NativeRoot)) {
    throw "Native Android folder not found: $NativeRoot"
}

Write-Host "==> Checking Node"
node --version
npm --version

Write-Host ""
Write-Host "==> Installing backend dependencies"
Push-Location $BackendRoot

if (!(Test-Path ".\node_modules")) {
    npm install
}

Write-Host ""
Write-Host "==> Backend typecheck"
npm run typecheck

Write-Host ""
Write-Host "==> Backend build"
npm run build

Pop-Location

Write-Host ""
Write-Host "==> Checking backend health"

$BackendRunning = $false

try {
    $Health = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$Port/health" `
        -TimeoutSec 3

    if ($Health.ok) {
        $BackendRunning = $true
        Write-Host "PASS: backend already running"
    }
}
catch {
    $BackendRunning = $false
}

if (!$BackendRunning) {

    Write-Host "Starting backend in separate PowerShell window..."

    Start-Process powershell.exe -ArgumentList @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-Command",
        "Set-Location '$BackendRoot'; npm run dev"
    )

    $Deadline = (Get-Date).AddSeconds(30)

    do {
        Start-Sleep -Seconds 1

        try {
            $Health = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$Port/health" `
                -TimeoutSec 2

            if ($Health.ok) {
                $BackendRunning = $true
                break
            }
        }
        catch {}
    }
    while ((Get-Date) -lt $Deadline)

    if (!$BackendRunning) {
        throw "Backend failed to start on port $Port"
    }
}

Write-Host "PASS: backend health"

Write-Host ""
Write-Host "==> Building Android APK"

Push-Location $NativeRoot

if (Test-Path ".\gradlew.bat") {

    & .\gradlew.bat assembleDebug

    if ($LASTEXITCODE -ne 0) {
        throw "Gradle assembleDebug failed"
    }

}
else {
    throw "gradlew.bat not found"
}

Pop-Location

$Apk = Get-ChildItem `
    "$NativeRoot\app\build\outputs\apk\debug" `
    -Filter "*.apk" `
    -ErrorAction SilentlyContinue |
Sort-Object LastWriteTime -Descending |
Select-Object -First 1

if (!$Apk) {
    throw "Debug APK not found"
}

Write-Host "PASS APK:"
Write-Host $Apk.FullName

Write-Host ""
Write-Host "==> Checking ADB"

adb version | Out-Host

$Devices = adb devices |
Select-Object -Skip 1 |
Where-Object { $_ -match "\sdevice$" }

if (!$Devices) {
    throw "No Android device connected through ADB"
}

Write-Host "PASS: Android device detected"

Write-Host ""
Write-Host "==> Creating USB reverse for backend"

adb reverse tcp:$Port tcp:$Port | Out-Host

Write-Host ""
Write-Host "==> Installing APK"

adb install -r $Apk.FullName | Out-Host

if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed"
}

Write-Host "PASS: APK installed"

$PackageName = "com.atomosremote.nativeapp"

Write-Host ""
Write-Host "==> Launching $PackageName"

adb shell monkey `
    -p $PackageName `
    -c android.intent.category.LAUNCHER `
    1 | Out-Host

Write-Host ""
Write-Host "============================================="
Write-Host " APK INSTALLED AND APP STARTED"
Write-Host "============================================="
Write-Host ""
Write-Host "NOW:"
Write-Host "1. Connect/pair AG2 normally inside the app."
Write-Host "2. Accept matching pairing code on recorder."
Write-Host "3. Wait until PacketLink/Remote is ready."
Write-Host ""
Write-Host "Then run:"
Write-Host ""
Write-Host ".\TEST-LOCAL-ALL-TABS.ps1"
Write-Host ""