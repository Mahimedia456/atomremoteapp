param(
    [string]$BaseUrl = "http://127.0.0.1:8787/api/v1",
    [string]$DeviceId = "ag2-main",
    [switch]$WriteTests
)

$ErrorActionPreference = "Continue"

function Test-Get {
    param(
        [string]$Name,
        [string]$Path
    )

    Write-Host ""
    Write-Host "============================================="
    Write-Host "GET TEST: $Name"
    Write-Host "============================================="

    try {
        $Result = Invoke-RestMethod `
            -Method GET `
            -Uri "$BaseUrl/devices/$DeviceId/$Path" `
            -TimeoutSec 25

        $Result | ConvertTo-Json -Depth 30

        Write-Host "PASS: $Name"
    }
    catch {
        Write-Host "FAIL: $Name"
        Write-Host $_.Exception.Message
    }
}

function Test-Post {
    param(
        [string]$Name,
        [string]$Path,
        [hashtable]$Body
    )

    Write-Host ""
    Write-Host "============================================="
    Write-Host "POST TEST: $Name"
    Write-Host "============================================="

    try {
        $Json = $Body | ConvertTo-Json -Depth 10

        $Result = Invoke-RestMethod `
            -Method POST `
            -Uri "$BaseUrl/devices/$DeviceId/$Path" `
            -ContentType "application/json" `
            -Body $Json `
            -TimeoutSec 25

        $Result | ConvertTo-Json -Depth 30

        Write-Host "PASS: $Name"
    }
    catch {
        Write-Host "FAIL: $Name"
        Write-Host $_.Exception.Message
    }
}

Write-Host ""
Write-Host "============================================="
Write-Host " ATOMREMOTE REAL AG2 LOCAL API TEST"
Write-Host "============================================="
Write-Host ""

Write-Host "Checking registered devices..."

try {
    $Devices = Invoke-RestMethod `
        -Uri "$BaseUrl/devices" `
        -TimeoutSec 5

    $Devices | ConvertTo-Json -Depth 20
}
catch {
    Write-Host "Cannot query devices."
    Write-Host $_.Exception.Message
    exit 1
}

$RealDevice = $Devices.devices |
Where-Object { $_.deviceId -eq $DeviceId }

if (!$RealDevice) {

    Write-Host ""
    Write-Host "ERROR: $DeviceId is not registered."
    Write-Host ""
    Write-Host "Current devices:"
    $Devices.devices | Format-Table

    Write-Host ""
    Write-Host "Make sure:"
    Write-Host "- Android app is running"
    Write-Host "- BackendAgentCoordinator is wired"
    Write-Host "- AG2 is connected"
    Write-Host "- Pairing is ACCEPTED"
    Write-Host "- PacketLink is ready"

    exit 1
}

Write-Host ""
Write-Host "PASS: Real Android agent found: $DeviceId"

# INPUT

Test-Get `
    -Name "Input" `
    -Path "input"

Test-Get `
    -Name "Input HDR" `
    -Path "input/hdr"

Test-Get `
    -Name "Input Output Summary" `
    -Path "input/summary"

# STATE

Test-Get `
    -Name "Application State" `
    -Path "state/application"

Test-Get `
    -Name "Monitor State" `
    -Path "state/monitor"

Test-Get `
    -Name "Record State" `
    -Path "state/record"

Test-Get `
    -Name "Output State" `
    -Path "state/output"

Test-Get `
    -Name "Audio Meters State" `
    -Path "state/audio-meters"

Test-Get `
    -Name "Monitor Assist State" `
    -Path "state/monitor-assist"

if (!$WriteTests) {

    Write-Host ""
    Write-Host "============================================="
    Write-Host " READ TESTS COMPLETE"
    Write-Host "============================================="
    Write-Host ""
    Write-Host "For visible recorder changes run:"
    Write-Host ""
    Write-Host ".\TEST-LOCAL-ALL-TABS.ps1 -WriteTests"
    exit 0
}

Write-Host ""
Write-Host "============================================="
Write-Host " STARTING SAFE WRITE TESTS"
Write-Host "============================================="

# FOCUS

Test-Post `
    -Name "Focus Assist ON" `
    -Path "monitor/focus" `
    -Body @{ enabled = $true }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Focus Assist OFF" `
    -Path "monitor/focus" `
    -Body @{ enabled = $false }

# ZEBRA

Test-Post `
    -Name "Zebra ON" `
    -Path "monitor/zebra" `
    -Body @{ enabled = $true }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Zebra OFF" `
    -Path "monitor/zebra" `
    -Body @{ enabled = $false }

# FALSE COLOR

Test-Post `
    -Name "False Color ON" `
    -Path "monitor/false-color" `
    -Body @{ enabled = $true }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "False Color OFF" `
    -Path "monitor/false-color" `
    -Body @{ enabled = $false }

# BLUE ONLY

Test-Post `
    -Name "Blue Only ON" `
    -Path "monitor/blue-only" `
    -Body @{ enabled = $true }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Blue Only OFF" `
    -Path "monitor/blue-only" `
    -Body @{ enabled = $false }

# SCOPES

Test-Post `
    -Name "Scopes ON" `
    -Path "scopes/enabled" `
    -Body @{ enabled = $true }

Test-Post `
    -Name "Scope Luma Parade" `
    -Path "scopes/mode" `
    -Body @{ mode = "luma-parade" }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Scope RGB Parade" `
    -Path "scopes/mode" `
    -Body @{ mode = "rgb-parade" }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Scope Vectorscope" `
    -Path "scopes/mode" `
    -Body @{ mode = "vectorscope" }

Test-Post `
    -Name "Scope Quarter Size" `
    -Path "scopes/size" `
    -Body @{ size = "quarter" }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Scope Full Size" `
    -Path "scopes/size" `
    -Body @{ size = "full" }

Test-Post `
    -Name "Scopes OFF" `
    -Path "scopes/enabled" `
    -Body @{ enabled = $false }

# FRAME GUIDE

Test-Post `
    -Name "Frame Guide ON" `
    -Path "guides/frame" `
    -Body @{ enabled = $true }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Frame Guide OFF" `
    -Path "guides/frame" `
    -Body @{ enabled = $false }

# SAFE AREA

Test-Post `
    -Name "Safe Area OFF" `
    -Path "guides/safe-area" `
    -Body @{ mode = 1 }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Safe Area Action" `
    -Path "guides/safe-area" `
    -Body @{ mode = 2 }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Safe Area Title" `
    -Path "guides/safe-area" `
    -Body @{ mode = 3 }

# ANAMORPHIC

Test-Post `
    -Name "Anamorphic None" `
    -Path "monitor/anamorphic" `
    -Body @{ mode = 0 }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Anamorphic 2x" `
    -Path "monitor/anamorphic" `
    -Body @{ mode = 2 }

Start-Sleep -Milliseconds 800

Test-Post `
    -Name "Anamorphic None Restore" `
    -Path "monitor/anamorphic" `
    -Body @{ mode = 0 }

# MONITOR MODE

Test-Post `
    -Name "Monitor Native" `
    -Path "monitor/mode" `
    -Body @{ mode = 1 }

Start-Sleep -Milliseconds 1000

Test-Post `
    -Name "Monitor Rec709" `
    -Path "monitor/mode" `
    -Body @{ mode = 2 }

Start-Sleep -Milliseconds 1000

Test-Post `
    -Name "Monitor Native Restore" `
    -Path "monitor/mode" `
    -Body @{ mode = 1 }

Write-Host ""
Write-Host "============================================="
Write-Host " SAFE WRITE TESTS COMPLETE"
Write-Host "============================================="