param(
    [string]$ProjectRoot = "E:\AtomosRemote",
    [string]$Device = ""
)
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (!(Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if (!$cmd) { throw "ADB not found. Expected: $adb" }
    $adb = $cmd.Source
}

Write-Host "Using ADB: $adb" -ForegroundColor DarkGray
if ($Device) {
    & $adb connect $Device | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "ADB connect failed: $Device" }
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$root = Join-Path $ProjectRoot "final-test\19.12-$stamp"
New-Item -ItemType Directory -Force -Path $root | Out-Null
$fullLog = Join-Path $root "FULL-LOGCAT-19.12.log"
$errLog = Join-Path $root "ADB-STDERR-19.12.log"
$summary = Join-Path $root "FEATURE-SUMMARY-19.12.txt"
$deviceInfo = Join-Path $root "DEVICE-INFO-19.12.txt"

if ($Device) {
    & $adb -s $Device logcat -c
    (& $adb -s $Device shell getprop) | Set-Content -Path $deviceInfo -Encoding UTF8
    $args = @('-s', $Device, 'logcat', '-v', 'threadtime')
} else {
    & $adb logcat -c
    (& $adb shell getprop) | Set-Content -Path $deviceInfo -Encoding UTF8
    $args = @('logcat', '-v', 'threadtime')
}

Write-Host "" 
Write-Host "MASTER 19.12 HARDWARE CAPTURE" -ForegroundColor Cyan
Write-Host "Perform ONE item at a time from MASTER-TEST-CHECKLIST-19.12.txt." -ForegroundColor Yellow
Write-Host "Full log: $fullLog"
Write-Host "When all tests are complete, return here and press ENTER. Do not use Ctrl+C." -ForegroundColor Yellow
Write-Host ""

$p = Start-Process -FilePath $adb -ArgumentList $args -RedirectStandardOutput $fullLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
try {
    Read-Host "Press ENTER after completing the entire ordered hardware test"
}
finally {
    if (!$p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 300
}

$patterns = @(
    'AtomRemoteGatt',
    'AtomRemotePair',
    'TCS19.',
    'TCS18.',
    'AndroidRuntime',
    'FATAL EXCEPTION',
    'APP CRASH',
    'Process: com.atomosremote.nativeapp',
    'Caused by:',
    'Exception',
    'SAFE AREA',
    'GRID',
    'ZOOM',
    'CODEC',
    'Input / Format',
    'Record Start',
    'Record Stop',
    'Play',
    'Monitor'
)
$regex = ($patterns | ForEach-Object { [regex]::Escape($_) }) -join '|'
if (Test-Path $fullLog) {
    Select-String -Path $fullLog -Pattern $regex -CaseSensitive:$false |
        ForEach-Object { $_.Line } |
        Set-Content -Path $summary -Encoding UTF8
}

Write-Host "CAPTURE COMPLETE" -ForegroundColor Cyan
Write-Host "Full log: $fullLog"
Write-Host "Feature summary: $summary"
Write-Host "Device info: $deviceInfo"
Write-Host "ADB stderr: $errLog"
Write-Host "Run MASTER-EXTRACT-RESULTS-19.12.ps1 -CaptureFolder `"$root`" next." -ForegroundColor Yellow
