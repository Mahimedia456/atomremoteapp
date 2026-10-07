param(
 [string]$ProjectRoot = "E:\AtomosRemote",
 [switch]$Install,
 [string]$Device = ""
)
$ErrorActionPreference="Stop"
$native=Join-Path $ProjectRoot "native-android"
$tools=Join-Path $ProjectRoot "tools\atomremote-final-19.12.8"
$verify=Join-Path $tools "VERIFY-PHASE-19.12.8.ps1"
if(!(Test-Path $verify)){throw "19.12.8 tools not installed. Run INSTALL-PHASE-19.12.8.ps1 first."}
& $verify -ProjectRoot $ProjectRoot
if($LASTEXITCODE -ne 0){throw "Source verifier failed."}
$locked=@(
 "app\src\main\java\com\atomosremote\nativeapp\ble\TcsPacketLinkTransport.kt",
 "app\src\main\java\com\atomosremote\nativeapp\ble\TcsConnectedDataTransfer.kt",
 "app\src\main\java\com\atomosremote\nativeapp\ble\BleModels.kt"
)
$hashes=@{};foreach($r in $locked){$p=Join-Path $native $r;if(Test-Path $p){$hashes[$r]=(Get-FileHash $p -Algorithm SHA256).Hash}}
$stamp=Get-Date -Format "yyyyMMdd_HHmmss";$out=Join-Path $ProjectRoot "final-test\19.12.8-build-$stamp";New-Item -ItemType Directory -Force -Path $out|Out-Null
Write-Host "=== 19.12.8 CLEAN BUILD ===" -ForegroundColor Cyan
Push-Location $native
try{& .\gradlew.bat clean assembleDebug;if($LASTEXITCODE -ne 0){throw "Gradle clean assembleDebug failed with exit code $LASTEXITCODE"}}finally{Pop-Location}
foreach($r in $hashes.Keys){$now=(Get-FileHash (Join-Path $native $r) -Algorithm SHA256).Hash;if($now -ne $hashes[$r]){throw "Frozen core changed during build: $r"}}
$apk=Get-ChildItem (Join-Path $native 'app\build\outputs\apk\debug') -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(!$apk){throw "Debug APK not found."}
Copy-Item -Force $apk.FullName (Join-Path $out $apk.Name)
$sha=(Get-FileHash $apk.FullName -Algorithm SHA256).Hash;Set-Content (Join-Path $out 'APK-SHA256.txt') "$sha  $($apk.Name)" -Encoding ASCII
Write-Host "BUILD PASS: $($apk.FullName)" -ForegroundColor Green
Write-Host "SHA256: $sha"
if($Install){
 $adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe';if(!(Test-Path $adb)){$cmd=Get-Command adb -ErrorAction SilentlyContinue;if(!$cmd){throw "ADB not found"};$adb=$cmd.Source}
 if($Device){& $adb connect $Device|Out-Host;& $adb -s $Device install -r $apk.FullName}else{& $adb install -r $apk.FullName}
 if($LASTEXITCODE -ne 0){throw "ADB install failed with exit code $LASTEXITCODE"}
 Write-Host "APK INSTALL PASS" -ForegroundColor Green
}
Write-Host "Artifacts: $out"
