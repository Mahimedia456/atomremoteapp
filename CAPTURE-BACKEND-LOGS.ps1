param([string]$ProjectRoot = "E:\AtomosRemoteWithBackend")
$ErrorActionPreference = "Stop"
$dir = Join-Path $ProjectRoot "local-test-logs"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$file = Join-Path $dir ("manual_android_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".log")
Write-Host "Capturing AtomRemote backend/GATT logs to: $file"
Write-Host "Press Ctrl+C to stop."
adb logcat -v time | Select-String "AtomRemoteBackend|AtomRemoteGatt|AtomRemotePair|BackendAgent" | Tee-Object -FilePath $file
