param([string]$ProjectRoot = "E:\AtomosRemote")
$ErrorActionPreference = "Stop"
$native = Join-Path $ProjectRoot "native-android"
$gattPath = Join-Path $native "app\src\main\java\com\atomosremote\nativeapp\ble\GattClient.kt"
$vmPath = Join-Path $native "app\src\main\java\com\atomosremote\nativeapp\viewmodel\AtomosViewModel.kt"
$uiPath = Join-Path $native "app\src\main\java\com\atomosremote\nativeapp\ui\AtomosApp.kt"
foreach ($p in @($gattPath,$vmPath,$uiPath)) { if (!(Test-Path $p)) { throw "Missing source: $p" } }
$gatt = Get-Content -Raw $gattPath
$vm = Get-Content -Raw $vmPath
$ui = Get-Content -Raw $uiPath
$checks = @(
 @{ok=$gatt.Contains('TCS19.12.1 SAFE AREA SET logical=');msg='Safe Area recovery source'},
 @{ok=$gatt.Contains('val wireMode = mode + 1');msg='Safe Area one-based wire mapping'},
 @{ok=$gatt.Contains('TCS19.12.1 GRID SET preserveGuide=');msg='Grid recovery source'},
 @{ok=$gatt.Contains('TCS19.12.1 ZOOM SET level=');msg='Zoom recovery source'},
 @{ok=$gatt.Contains('REMOTE_RECORD_CODEC_PROFILE_GET = 0x013A');msg='Codec GET id retained'},
 @{ok=$gatt.Contains('REMOTE_RECORD_CODEC_PROFILE_SET = 0x013B');msg='Codec restart setter retained'},
 @{ok=$gatt.Contains('REMOTE_RECORD_NON_REBOOT_CODEC_PROFILE_SET = 0x013F');msg='Codec non-reboot setter retained'},
 @{ok=$gatt.Contains('refreshOfficialInputOutputSummary');msg='Input/output summary path retained'},
 @{ok=$vm.Contains('TCS19.12.8 NAV');msg='19.12.8 focused navigation diagnostics'},
 @{ok=$ui.Contains('TCS19.12.8 TAB INPUT');msg='Input snapshot diagnostics'},
 @{ok=$ui.Contains('TCS19.12.8 TAB AUDIO');msg='Audio snapshot diagnostics'},
 @{ok=$ui.Contains('TCS19.12.8 TAB MEDIA');msg='Media snapshot diagnostics'},
 @{ok=$ui.Contains('TCS19.12.8 TAB OUTPUT');msg='Output snapshot diagnostics'},
 @{ok=$ui.Contains('TCS19.12.8 TAB SETTINGS');msg='Settings snapshot diagnostics'},
 @{ok=$ui.Contains('InputModuleScreen(vm)') -and $ui.Contains('AudioModuleScreen(vm)') -and $ui.Contains('MediaModuleScreen(vm)') -and $ui.Contains('OutputModuleScreen(vm)') -and $ui.Contains('SettingsModuleScreen(vm)');msg='All five modules present'},
 @{ok=$ui.Contains('RECORDER LINKED') -and $ui.Contains('WAITING FOR RECORDER');msg='Recorder session strip retained'}
)
$failed=0
Write-Host "=== AtomRemote Phase 19.12.8 Source Verify ===" -ForegroundColor Cyan
foreach($c in $checks){ if($c.ok){Write-Host "PASS: $($c.msg)" -ForegroundColor Green}else{Write-Host "FAIL: $($c.msg)" -ForegroundColor Red;$failed++} }
if($failed -gt 0){ throw "19.12.8 source verification failed: $failed check(s)." }
Write-Host "PHASE 19.12.8 SOURCE VERIFY PASS" -ForegroundColor Cyan
