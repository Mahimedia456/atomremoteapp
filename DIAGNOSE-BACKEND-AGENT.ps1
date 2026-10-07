param(
    [string]$ProjectRoot = "E:\AtomosRemoteWithBackend",
    [string]$AdbSerial = "192.168.100.4:40731",
    [int]$Port = 8787
)

$ErrorActionPreference = "Continue"

function Print-Http($Name, $Url) {
    Write-Host ""
    Write-Host "===================================================="
    Write-Host $Name
    Write-Host "===================================================="
    curl.exe -sS -i $Url
    Write-Host ""
}

Write-Host ""
Write-Host "ATOMREMOTE BACKEND DIAGNOSTIC"
Write-Host "ADB: $AdbSerial"

Write-Host "`n== ADB DEVICE =="
adb devices

Write-Host "`n== ADB REVERSE =="
adb -s $AdbSerial reverse --list

Print-Http "DEVICES" "http://127.0.0.1:$Port/api/v1/devices"
Print-Http "INPUT" "http://127.0.0.1:$Port/api/v1/devices/ag2-main/input"
Print-Http "MONITOR STATE" "http://127.0.0.1:$Port/api/v1/devices/ag2-main/state/monitor"

Write-Host ""
Write-Host "== BACKEND AGENT LOGS =="
adb -s $AdbSerial logcat -d -v time |
    Select-String "AtomRemoteBackend|COLLECTOR BLOCKED|STATE REFRESH blocked|CONNECT_NAME|CCID|BCID|ACCEPT WRITE|ACCEPTED|PacketLink|PLINK|TCS18|TCS19" |
    Select-Object -Last 220
