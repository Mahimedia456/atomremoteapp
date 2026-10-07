param([string]$ProjectRoot = "E:\AtomosRemoteWithBackend")
$ErrorActionPreference = "Stop"
$client = Join-Path $ProjectRoot "native-android\app\src\main\java\com\atomosremote\nativeapp\backend\BackendAgentClient.kt"
if (!(Test-Path $client)) { throw "Missing: $client" }
$text = Get-Content $client -Raw
$checks = @(
    @("Persistent watchdog", "scheduleWithFixedDelay"),
    @("Agent start log", "agent start deviceId="),
    @("Registered log", "agent ready REGISTERED"),
    @("Reconnect send-result", "send-result-no-socket"),
    @("Backend package wiring", "AtomRemoteBackend")
)
foreach ($c in $checks) {
    if ($text.Contains($c[1])) { Write-Host "PASS $($c[0])" } else { throw "FAIL $($c[0])" }
}
Write-Host "AGENT RECONNECT PATCH VERIFY PASS"
