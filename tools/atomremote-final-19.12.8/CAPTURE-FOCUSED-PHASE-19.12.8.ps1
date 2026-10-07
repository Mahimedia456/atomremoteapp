param(
 [string]$ProjectRoot = "E:\AtomosRemote",
 [string]$Device = ""
)
$ErrorActionPreference="Stop"
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe';if(!(Test-Path $adb)){$cmd=Get-Command adb -ErrorAction SilentlyContinue;if(!$cmd){throw "ADB not found"};$adb=$cmd.Source}
if($Device){& $adb connect $Device|Out-Host;if($LASTEXITCODE -ne 0){throw "ADB connect failed: $Device"}}
$stamp=Get-Date -Format 'yyyyMMdd_HHmmss';$root=Join-Path $ProjectRoot "final-test\19.12.8-focused-$stamp";New-Item -ItemType Directory -Force -Path $root|Out-Null
$stages=@(
 @{n='01-TRANSPORT';p='Test Record Start, Stop, Play, Pause only.'},
 @{n='02-SAFE-AREA';p='Tap Safe Area through Action, Title, Off. Watch recorder physically.'},
 @{n='03-GRID';p='Test 9-Grid ON then OFF only.'},
 @{n='04-ZOOM';p='Test Zoom 1:1, 2:1, then Off only.'},
 @{n='05-CODEC';p='Select ProRes 422 and APPLY. Use APPLY + RESTART only if recorder is stopped and normal apply does not change it.'},
 @{n='06-MONITOR-MODES';p='Test Native, Rec.709, HLG, PQ, LUT only.'},
 @{n='07-INPUT-TAB';p='Open Input tab, wait 3 seconds, press REFRESH SIGNAL once, wait 3 seconds.'},
 @{n='08-AUDIO-TAB';p='Open Audio tab, wait 3 seconds, select CH1 then CH2.'},
 @{n='09-MEDIA-TAB';p='Open Media tab and wait 5 seconds. Do not format media.'},
 @{n='10-OUTPUT-TAB';p='Open Output tab, wait 3 seconds, press REFRESH OUTPUT once, wait 3 seconds.'},
 @{n='11-SETTINGS-TAB';p='Open Settings tab and wait 5 seconds.'},
 @{n='12-NAV-CRASH';p='Move Remote -> Input -> Audio -> Media -> Output -> Settings -> Remote. If app crashes, do not reopen until this stage is stopped.'}
)
$filter=@('logcat','-v','threadtime','AtomRemoteGatt:D','AtomRemotePair:D','AtomRemoteSession:D','AtomRemoteUI:D','AndroidRuntime:E','ActivityManager:E','*:S')
Write-Host "=== AtomRemote 19.12.8 FOCUSED HARDWARE CAPTURE ===" -ForegroundColor Cyan
Write-Host "Each feature gets its own filtered log. System noise is suppressed." -ForegroundColor Yellow
Write-Host "Output folder: $root"
foreach($s in $stages){
 Read-Host "Press ENTER when ready to start $($s.n)"
 if($Device){& $adb -s $Device logcat -c}else{& $adb logcat -c}
 $log=Join-Path $root ($s.n + '.log');$err=Join-Path $root ($s.n + '.stderr.log')
 $args=@();if($Device){$args+=@('-s',$Device)};$args+=$filter
 $p=Start-Process -FilePath $adb -ArgumentList $args -RedirectStandardOutput $log -RedirectStandardError $err -PassThru -WindowStyle Hidden
 Write-Host "";Write-Host $s.p -ForegroundColor Cyan
 Read-Host "Perform ONLY this test, then press ENTER"
 if(!$p.HasExited){Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue};Start-Sleep -Milliseconds 350
 Write-Host "Saved: $log" -ForegroundColor Green
}
$extract=Join-Path $ProjectRoot 'tools\atomremote-final-19.12.8\EXTRACT-FOCUSED-RESULTS-19.12.8.ps1'
if(Test-Path $extract){& $extract -CaptureFolder $root}
Write-Host "FOCUSED CAPTURE COMPLETE" -ForegroundColor Cyan
Write-Host "Send the whole folder or FINAL-RESULTS-19.12.8.txt plus the individual logs that show failures." -ForegroundColor Yellow
