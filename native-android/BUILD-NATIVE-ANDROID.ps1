$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Project
$Wrapper = Join-Path $Project 'gradlew.bat'

if (-not (Test-Path $Wrapper)) {
  & (Join-Path $Root 'BOOTSTRAP-NATIVE-GRADLE.ps1')
  if ($LASTEXITCODE -ne 0) { throw "Gradle bootstrap failed with exit code $LASTEXITCODE" }
}

Push-Location $Project
try {
  Write-Host 'Building native Android debug APK with JVM 17...' -ForegroundColor Cyan
  & .\gradlew.bat clean assembleDebug
  if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

  $apk = Join-Path $Project 'app\build\outputs\apk\debug\app-debug.apk'
  if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

  Write-Host "APK ready: $apk" -ForegroundColor Green

  if (Get-Command adb -ErrorAction SilentlyContinue) {
    $devices = & adb devices
    if ($devices -match '\tdevice') {
      Write-Host 'Installing APK on connected Android device...'
      & adb install -r $apk
      if ($LASTEXITCODE -ne 0) { throw "ADB install failed with exit code $LASTEXITCODE" }
    }
  }
} finally {
  Pop-Location
}
