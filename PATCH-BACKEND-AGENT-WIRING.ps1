param(
    [string]$ProjectRoot = "E:\AtomosRemoteWithBackend"
)

$ErrorActionPreference = "Stop"

Set-ExecutionPolicy -Scope Process Bypass -Force

$SrcRoot = Join-Path `
    $ProjectRoot `
    "native-android\app\src\main\java"

if (!(Test-Path $SrcRoot)) {
    throw "Android source not found: $SrcRoot"
}

Write-Host ""
Write-Host "==============================================="
Write-Host " AtomRemote Backend Agent Wiring"
Write-Host "==============================================="
Write-Host "Project: $ProjectRoot"
Write-Host ""

# ---------------------------------------------------------
# 1. Locate actual owner of existing GattClient
# ---------------------------------------------------------

$Candidates = @()

Get-ChildItem $SrcRoot -Recurse -Filter "*.kt" | ForEach-Object {

    $File = $_

    if ($File.Name -eq "GattClient.kt") {
        return
    }

    if ($File.FullName -match "\\backend\\BackendAgentCoordinator\.kt$") {
        return
    }

    $Text = Get-Content $File.FullName -Raw

    $Patterns = @(
        '(?m)^\s*(?:private\s+|internal\s+|public\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*GattClient\(',
        '(?m)^\s*(?:private\s+|internal\s+|public\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*GattClient\s*=\s*GattClient\('
    )

    foreach ($Pattern in $Patterns) {

        $Match = [regex]::Match($Text, $Pattern)

        if ($Match.Success) {

            $Score = 0

            if ($File.Name -match "ViewModel") {
                $Score += 100
            }

            if ($Text -match "AndroidViewModel|ViewModel") {
                $Score += 50
            }

            if ($Text -match "viewModelScope") {
                $Score += 20
            }

            $Candidates += [PSCustomObject]@{
                File     = $File.FullName
                Variable = $Match.Groups[1].Value
                Score    = $Score
            }

            break
        }
    }
}

if ($Candidates.Count -eq 0) {

    Write-Host ""
    Write-Host "No automatic GattClient owner found."
    Write-Host "Showing every GattClient occurrence:"
    Write-Host ""

    Get-ChildItem $SrcRoot -Recurse -Filter "*.kt" |
        Select-String "GattClient\(" |
        Select-Object Path, LineNumber, Line |
        Format-Table -AutoSize

    throw "Could not safely determine GattClient owner."
}

$Selected = $Candidates |
    Sort-Object Score -Descending |
    Select-Object -First 1

$OwnerFile = $Selected.File
$GattName  = $Selected.Variable

Write-Host "PASS: GattClient owner located"
Write-Host "File    : $OwnerFile"
Write-Host "Variable: $GattName"
Write-Host ""

# ---------------------------------------------------------
# 2. Backup
# ---------------------------------------------------------

$Stamp = Get-Date -Format "yyyyMMdd_HHmmss"

$BackupRoot = Join-Path `
    $ProjectRoot `
    "phase-backups\backend-agent-wiring_$Stamp"

New-Item `
    -ItemType Directory `
    -Path $BackupRoot `
    -Force |
Out-Null

Copy-Item `
    $OwnerFile `
    (Join-Path $BackupRoot ([IO.Path]::GetFileName($OwnerFile))) `
    -Force

Write-Host "BACKUP: $BackupRoot"

$Text = Get-Content $OwnerFile -Raw

$NewLine = "`r`n"

if ($Text -notmatch "`r`n") {
    $NewLine = "`n"
}

# ---------------------------------------------------------
# 3. Add imports
# ---------------------------------------------------------

function Add-KotlinImport {

    param(
        [string]$Source,
        [string]$Import
    )

    if ($Source -match [regex]::Escape("import $Import")) {
        return $Source
    }

    $ImportLine = "import $Import"

    $ImportMatches = [regex]::Matches(
        $Source,
        '(?m)^import\s+.+$'
    )

    if ($ImportMatches.Count -gt 0) {

        $Last = $ImportMatches[$ImportMatches.Count - 1]

        return $Source.Insert(
            $Last.Index + $Last.Length,
            $NewLine + $ImportLine
        )
    }

    $PackageMatch = [regex]::Match(
        $Source,
        '(?m)^package\s+.+$'
    )

    if (!$PackageMatch.Success) {
        throw "Kotlin package declaration not found."
    }

    return $Source.Insert(
        $PackageMatch.Index + $PackageMatch.Length,
        $NewLine + $NewLine + $ImportLine
    )
}

$Text = Add-KotlinImport `
    -Source $Text `
    -Import "com.atomosremote.nativeapp.backend.BackendAgentCoordinator"

$Text = Add-KotlinImport `
    -Source $Text `
    -Import "com.atomosremote.nativeapp.backend.BackendInputRepository"

$Text = Add-KotlinImport `
    -Source $Text `
    -Import "androidx.lifecycle.viewModelScope"

# ---------------------------------------------------------
# 4. Insert backend coordinator using SAME GattClient
# ---------------------------------------------------------

if ($Text -notmatch 'private\s+val\s+backendAgent\s*=') {

    $GattDeclarationPattern = @"
(?m)^(?<line>\s*(?:private\s+|internal\s+|public\s+)?val\s+$GattName(?:\s*:\s*GattClient)?\s*=\s*GattClient\([^\r\n]*\)\s*)$
"@

    $GattDeclaration = [regex]::Match(
        $Text,
        $GattDeclarationPattern.Trim()
    )

    if (!$GattDeclaration.Success) {

        throw @"
Could not safely patch the GattClient declaration.

Detected variable:
$GattName

Detected file:
$OwnerFile

No source file has been modified.
"@
    }

    $IndentMatch = [regex]::Match(
        $GattDeclaration.Groups["line"].Value,
        '^\s*'
    )

    $Indent = $IndentMatch.Value

    $AgentBlock = @"

${Indent}private val backendAgent = BackendAgentCoordinator(
${Indent}    gattClient = $GattName,
${Indent}    scope = viewModelScope
${Indent})

${Indent}private val backendInputRepository = BackendInputRepository()
"@

    $Text = $Text.Insert(
        $GattDeclaration.Index + $GattDeclaration.Length,
        $AgentBlock
    )

    Write-Host "PATCH: BackendAgentCoordinator added"
}
else {
    Write-Host "PASS: BackendAgentCoordinator already instantiated"
}

# ---------------------------------------------------------
# 5. Start agent from existing init block
# ---------------------------------------------------------

if ($Text -notmatch 'backendAgent\.start\(\)') {

    $InitMatch = [regex]::Match(
        $Text,
        '(?m)^(?<indent>\s*)init\s*\{'
    )

    if ($InitMatch.Success) {

        $Indent = $InitMatch.Groups["indent"].Value

        $Replacement =
            $InitMatch.Value +
            $NewLine +
            $Indent +
            "    backendAgent.start()"

        $Text =
            $Text.Substring(0, $InitMatch.Index) +
            $Replacement +
            $Text.Substring(
                $InitMatch.Index + $InitMatch.Length
            )

        Write-Host "PATCH: backendAgent.start() added to init"
    }
    else {

        $AgentField = [regex]::Match(
            $Text,
            '(?s)private\s+val\s+backendAgent\s*=\s*BackendAgentCoordinator\(.*?\)\s*'
        )

        if (!$AgentField.Success) {
            throw "Unable to insert init block safely."
        }

        $InitBlock = @"

    init {
        backendAgent.start()
    }

"@

        $Text = $Text.Insert(
            $AgentField.Index + $AgentField.Length,
            $InitBlock
        )

        Write-Host "PATCH: new init block created"
    }
}
else {
    Write-Host "PASS: backendAgent.start() already present"
}

# ---------------------------------------------------------
# 6. Stop agent from onCleared()
# ---------------------------------------------------------

if ($Text -notmatch 'backendAgent\.stop\(\)') {

    $OnClearedMatch = [regex]::Match(
        $Text,
        '(?m)^(?<indent>\s*)override\s+fun\s+onCleared\s*\(\s*\)\s*\{'
    )

    if ($OnClearedMatch.Success) {

        $Indent = $OnClearedMatch.Groups["indent"].Value

        $Replacement =
            $OnClearedMatch.Value +
            $NewLine +
            $Indent +
            "    backendAgent.stop()"

        $Text =
            $Text.Substring(0, $OnClearedMatch.Index) +
            $Replacement +
            $Text.Substring(
                $OnClearedMatch.Index +
                $OnClearedMatch.Length
            )

        Write-Host "PATCH: backendAgent.stop() added to onCleared"
    }
    else {

        $LastBrace = $Text.LastIndexOf("}")

        if ($LastBrace -lt 0) {
            throw "Could not find class closing brace."
        }

        $OnClearedBlock = @"

    override fun onCleared() {
        backendAgent.stop()
        super.onCleared()
    }

"@

        $Text = $Text.Insert(
            $LastBrace,
            $OnClearedBlock
        )

        Write-Host "PATCH: onCleared() created"
    }
}
else {
    Write-Host "PASS: backendAgent.stop() already present"
}

# ---------------------------------------------------------
# 7. Write updated owner
# ---------------------------------------------------------

Set-Content `
    -Path $OwnerFile `
    -Value $Text `
    -Encoding UTF8

Write-Host "PASS: owner file patched"

# ---------------------------------------------------------
# 8. Force local USB backend mode
# ---------------------------------------------------------

$BridgeConfig = Get-ChildItem `
    $SrcRoot `
    -Recurse `
    -Filter "BackendBridgeConfig.kt" |
    Select-Object -First 1

if ($BridgeConfig) {

    $Cfg = Get-Content $BridgeConfig.FullName -Raw

    $Cfg = [regex]::Replace(
        $Cfg,
        'DEFAULT_WS_URL\s*=\s*"[^"]+"',
        'DEFAULT_WS_URL = "ws://127.0.0.1:8787/ws/agent"'
    )

    $Cfg = [regex]::Replace(
        $Cfg,
        'DEFAULT_HTTP_URL\s*=\s*"[^"]+"',
        'DEFAULT_HTTP_URL = "http://127.0.0.1:8787/api/v1"'
    )

    Set-Content `
        -Path $BridgeConfig.FullName `
        -Value $Cfg `
        -Encoding UTF8

    Write-Host "PATCH: BackendBridgeConfig set to localhost"
}
else {
    Write-Host "INFO: BackendBridgeConfig.kt not found"
}

# ---------------------------------------------------------
# 9. Force runtime LOCAL mode if Phase 10-12 is installed
# ---------------------------------------------------------

$RuntimeConfig = Get-ChildItem `
    $SrcRoot `
    -Recurse `
    -Filter "BackendRuntimeConfig.kt" |
    Select-Object -First 1

if ($RuntimeConfig) {

    $Runtime = Get-Content $RuntimeConfig.FullName -Raw

    $Runtime = [regex]::Replace(
        $Runtime,
        'MODE_NAME\s*=\s*"[^"]+"',
        'MODE_NAME = "LOCAL"'
    )

    Set-Content `
        -Path $RuntimeConfig.FullName `
        -Value $Runtime `
        -Encoding UTF8

    Write-Host "PATCH: BackendRuntimeConfig MODE_NAME=LOCAL"
}

# ---------------------------------------------------------
# 10. Final static verification
# ---------------------------------------------------------

Write-Host ""
Write-Host "==============================================="
Write-Host " Wiring verification"
Write-Host "==============================================="

$Final = Get-Content $OwnerFile -Raw

$Checks = @(
    @{
        Name = "Coordinator instance"
        Text = "BackendAgentCoordinator("
    },
    @{
        Name = "Same GattClient"
        Text = "gattClient = $GattName"
    },
    @{
        Name = "Agent start"
        Text = "backendAgent.start()"
    },
    @{
        Name = "Agent stop"
        Text = "backendAgent.stop()"
    }
)

foreach ($Check in $Checks) {

    if ($Final.Contains($Check.Text)) {
        Write-Host "PASS: $($Check.Name)"
    }
    else {
        throw "FAIL: $($Check.Name)"
    }
}

Write-Host ""
Write-Host "OWNER FILE:"
Write-Host $OwnerFile

Write-Host ""
Write-Host "BACKUP:"
Write-Host $BackupRoot

Write-Host ""
Write-Host "BACKEND AGENT WIRING PATCH PASS"