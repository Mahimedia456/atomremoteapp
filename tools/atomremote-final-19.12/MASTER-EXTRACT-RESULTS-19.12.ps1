param(
    [Parameter(Mandatory=$true)][string]$CaptureFolder
)
$ErrorActionPreference = "Stop"
$full = Join-Path $CaptureFolder "FULL-LOGCAT-19.12.log"
if (!(Test-Path $full)) { throw "Full log not found: $full" }
$out = Join-Path $CaptureFolder "AUTO-RESULTS-19.12.txt"
$log = Get-Content -Raw $full

function CountMatch([string]$pattern) {
    return ([regex]::Matches($log, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)).Count
}

$rows = @(
    @('Crash markers', 'FATAL EXCEPTION|APP CRASH\(EXCEPTION\)|Process: com\.atomosremote\.nativeapp'),
    @('Safe Area', 'SAFE AREA'),
    @('9-Grid', 'GRID'),
    @('Zoom', 'ZOOM'),
    @('Codec', 'CODEC'),
    @('Input / Format', 'Input / Format|InputOutputSummary'),
    @('Record Start', 'Record Start'),
    @('Record Stop', 'Record Stop'),
    @('Play/Pause', 'Play Start|Play Pause|Play'),
    @('Monitor Modes', 'Rec\.709|HLG|PQ|LUT|Native')
)

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('ATOMREMOTE 19.12 AUTO EXTRACTION')
$lines.Add('This file counts evidence only. It does NOT declare a feature working.')
$lines.Add('Physical recorder behavior remains the source of truth.')
$lines.Add('')
foreach ($r in $rows) {
    $count = CountMatch $r[1]
    $lines.Add(('{0,-24} matches={1}' -f $r[0], $count))
}
$lines.Add('')
$lines.Add('Use TEST-RESULTS-TEMPLATE-19.12.txt to classify each feature as:')
$lines.Add('WORKING / PARTIAL / REJECTED / NO RESPONSE / UI ONLY / CRASH')
$lines | Set-Content -Path $out -Encoding UTF8
Write-Host "Created: $out" -ForegroundColor Green
