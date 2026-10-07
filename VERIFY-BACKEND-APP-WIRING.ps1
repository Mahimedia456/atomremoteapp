param([string]$ProjectRoot = "E:\AtomosRemoteWithBackend")
$ErrorActionPreference = "Stop"
$src = Join-Path $ProjectRoot "native-android\app\src\main\java"
$gradle = Join-Path $ProjectRoot "native-android\app\build.gradle.kts"
$vm = Join-Path $src "com\atomosremote\nativeapp\viewmodel\AtomosViewModel.kt"
$runtime = Join-Path $src "com\atomosremote\nativeapp\backend\BackendRuntimeConfig.kt"

$checks = @(
    @{Name="Separate applicationId"; File=$gradle; Pattern='applicationId = "com.atomosremote.nativeapp.backend"'},
    @{Name="Coordinator import"; File=$vm; Pattern='BackendAgentCoordinator'},
    @{Name="Same gatt instance"; File=$vm; Pattern='gattClient = gatt'},
    @{Name="Agent start"; File=$vm; Pattern='backendAgent.start()'},
    @{Name="Agent stop"; File=$vm; Pattern='backendAgent.stop()'},
    @{Name="LOCAL mode"; File=$runtime; Pattern='MODE_NAME = "LOCAL"'},
    @{Name="ADB reverse localhost WS"; File=$runtime; Pattern='ws://127.0.0.1:8787/ws/agent'}
)
foreach($c in $checks) {
    if (!(Test-Path $c.File)) { throw "FAIL $($c.Name): missing $($c.File)" }
    $raw = Get-Content $c.File -Raw
    if (!$raw.Contains($c.Pattern)) { throw "FAIL $($c.Name): '$($c.Pattern)' not found" }
    Write-Host "PASS $($c.Name)"
}
Write-Host "BACKEND APP WIRING VERIFY PASS"
