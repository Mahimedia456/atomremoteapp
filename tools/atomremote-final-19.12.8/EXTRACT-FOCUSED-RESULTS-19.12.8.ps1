param([Parameter(Mandatory=$true)][string]$CaptureFolder)
$ErrorActionPreference='Stop'
if(!(Test-Path $CaptureFolder)){throw "Capture folder not found: $CaptureFolder"}
$out=Join-Path $CaptureFolder 'FINAL-RESULTS-19.12.8.txt'
$lines=@('AtomRemote 19.12.8 focused capture result','Generated: '+(Get-Date),'')
$pattern='TCS19\.|TCS18\.|AtomRemoteUI|SAFE AREA|GRID|ZOOM|CODEC|Record Start|Record Stop|Play|Monitor|FATAL EXCEPTION|APP CRASH|Process: com\.atomosremote\.nativeapp|Caused by:'
Get-ChildItem $CaptureFolder -Filter '*.log' -File | Where-Object {$_.Name -notlike '*.stderr.log'} | Sort-Object Name | ForEach-Object {
 $name=$_.BaseName;$txt=Get-Content -Raw $_.FullName
 $status='CAPTURED'
 if($txt -match 'FATAL EXCEPTION|APP CRASH'){ $status='CRASH' }
 elseif($txt -match 'response=0xFFFF|\bREJECT\b'){ $status='RECORDER_REJECTED_COMMAND' }
 elseif($name -match 'AUDIO-TAB|MEDIA-TAB|SETTINGS-TAB' -and $txt -match 'NOT_WIRED'){ $status='TAB_OPENS_DATA_NOT_WIRED' }
 elseif($name -match 'INPUT-TAB|OUTPUT-TAB' -and $txt -notmatch 'TCS19\.12\.8 TAB'){ $status='NO_TAB_SNAPSHOT' }
 $acks=([regex]::Matches($txt,'response=0x8000')).Count
 $lines += "[$name] STATUS=$status RECORDER_ACK_0x8000=$acks"
 $summary=Join-Path $CaptureFolder ($name+'.summary.txt')
 Select-String -Path $_.FullName -Pattern $pattern -CaseSensitive:$false | ForEach-Object {$_.Line} | Set-Content $summary -Encoding UTF8
}
$lines += ''
$lines += 'Important: RECORDER_ACK_0x8000 is protocol acknowledgement only. Physical recorder behavior must be recorded separately.'
$lines | Set-Content $out -Encoding UTF8
Write-Host "Result summary: $out" -ForegroundColor Green
