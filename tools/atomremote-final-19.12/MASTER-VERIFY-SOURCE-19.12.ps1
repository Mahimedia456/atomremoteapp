param(
    [string]$ProjectRoot = "E:\AtomosRemote"
)
$ErrorActionPreference = "Stop"
$native = Join-Path $ProjectRoot "native-android"
$gattPath = Join-Path $native "app\src\main\java\com\atomosremote\nativeapp\ble\GattClient.kt"
$vmPath = Join-Path $native "app\src\main\java\com\atomosremote\nativeapp\viewmodel\AtomosViewModel.kt"
$uiPath = Join-Path $native "app\src\main\java\com\atomosremote\nativeapp\ui\AtomosApp.kt"

foreach ($p in @($gattPath,$vmPath,$uiPath)) {
    if (!(Test-Path $p)) { throw "Missing source: $p" }
}
$gatt = Get-Content -Raw $gattPath
$vm = Get-Content -Raw $vmPath
$ui = Get-Content -Raw $uiPath

$checks = @(
    @{ ok = $gatt.Contains('TCS19.12.1 SAFE AREA SET logical='); msg = 'Safe Area one-based hardware recovery source' },
    @{ ok = $gatt.Contains('val wireMode = mode + 1'); msg = 'Safe Area logical-to-wire mapping' },
    @{ ok = $gatt.Contains('TCS19.12.1 GRID SET preserveGuide='); msg = '9-Grid recovered setter path' },
    @{ ok = $gatt.Contains('local = false') -and $gatt.Contains('remote-guide-grid-set:'); msg = '9-Grid remote envelope path' },
    @{ ok = $gatt.Contains('TCS19.12.1 ZOOM SET level='); msg = 'Zoom direct setter path' },
    @{ ok = $gatt.Contains('REMOTE_MONITOR_ZOOM_LEVEL_SET = 0x0272'); msg = 'Zoom command id retained' },
    @{ ok = $gatt.Contains('REMOTE_RECORD_CODEC_PROFILE_GET = 0x013A'); msg = 'Codec get id retained' },
    @{ ok = $gatt.Contains('REMOTE_RECORD_CODEC_PROFILE_SET = 0x013B'); msg = 'Codec restart setter id retained' },
    @{ ok = $gatt.Contains('REMOTE_RECORD_NON_REBOOT_CODEC_PROFILE_SET = 0x013F'); msg = 'Codec non-reboot setter id retained' },
    @{ ok = $gatt.Contains('InputOutputSummaryGet') -and $gatt.Contains('refreshOfficialInputOutputSummary'); msg = 'Input read-only summary path' },
    @{ ok = $ui.Contains('InputModuleScreen(vm)'); msg = 'Input module' },
    @{ ok = $ui.Contains('AudioModuleScreen(vm)'); msg = 'Audio module' },
    @{ ok = $ui.Contains('MediaModuleScreen(vm)'); msg = 'Media module' },
    @{ ok = $ui.Contains('OutputModuleScreen(vm)'); msg = 'Output module' },
    @{ ok = $ui.Contains('SettingsModuleScreen(vm)'); msg = 'Settings module' },
    @{ ok = $ui.Contains('RECORDER LINKED') -and $ui.Contains('WAITING FOR RECORDER'); msg = 'UI recorder state consistency' },
    @{ ok = $vm.Contains('refreshInputFormatSummary'); msg = 'ViewModel Input bridge' }
)

$failed = 0
Write-Host "=== AtomRemote Final Source Verify 19.12 ===" -ForegroundColor Cyan
foreach ($c in $checks) {
    if ($c.ok) { Write-Host "PASS: $($c.msg)" -ForegroundColor Green }
    else { Write-Host "FAIL: $($c.msg)" -ForegroundColor Red; $failed++ }
}

$forbidden = @(
    "app\src\main\java\com\atomosremote\nativeapp\ble\TcsPacketLinkTransport.kt",
    "app\src\main\java\com\atomosremote\nativeapp\ble\TcsConnectedDataTransfer.kt",
    "app\src\main\java\com\atomosremote\nativeapp\ble\BleModels.kt"
)
foreach ($r in $forbidden) {
    if (Test-Path (Join-Path $native $r)) { Write-Host "PRESENT FROZEN CORE: $r" -ForegroundColor DarkGray }
}

if ($failed -gt 0) {
    Write-Host "FINAL SOURCE VERIFY FAILED: $failed check(s)." -ForegroundColor Red
    exit 1
}
Write-Host "FINAL SOURCE VERIFY PASS" -ForegroundColor Cyan
exit 0
