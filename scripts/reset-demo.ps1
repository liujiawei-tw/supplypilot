[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AllowedDatabase,
    [Parameter(Mandatory = $true)]
    [string]$AllowedSemanticDatabase
)

$ErrorActionPreference = 'Stop'

function Test-ExplicitDemoTargetName {
    param([string]$Name)

    return -not [string]::IsNullOrWhiteSpace($Name) -and
        $Name -match '(?i)(^|[-_.])demo($|[-_.])' -and
        $Name -notmatch '(?i)(^|[-_.])(prod|production|shared|common)($|[-_.])'
}

function Resolve-DemoStorageRoot {
    param(
        [Parameter(Mandatory = $true)][string]$ConfiguredPath,
        [Parameter(Mandatory = $true)][string]$BackendRoot
    )

    if ([IO.Path]::IsPathRooted($ConfiguredPath)) {
        return [IO.Path]::GetFullPath($ConfiguredPath)
    }

    return [IO.Path]::GetFullPath((Join-Path $BackendRoot $ConfiguredPath))
}

function Assert-DemoStorageRoot {
    param([Parameter(Mandatory = $true)][string]$StorageRoot)

    $trimmedStorageRoot = $StorageRoot.TrimEnd(
        [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    )
    $storageRootName = [IO.Path]::GetFileName($trimmedStorageRoot)
    if (-not (Test-ExplicitDemoTargetName $storageRootName)) {
        throw 'The DOCUMENT_STORAGE_PATH root directory itself must be explicitly demo-isolated.'
    }
}

function Invoke-DemoResetCommand {
    param(
        [Parameter(Mandatory = $true)][string]$BackendRoot,
        [Parameter(Mandatory = $true)][string]$StorageRoot,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$SemanticDatabase,
        [Parameter(Mandatory = $true)][scriptblock]$CommandRunner
    )

    # Keep this guard inside the injectable command boundary so no environment or
    # process state can be changed before an unsafe storage root is rejected.
    Assert-DemoStorageRoot -StorageRoot $StorageRoot

    $managedEnvironment = @(
        'DEMO_RESET_CONFIRMATION',
        'DEMO_ALLOWED_DATABASE',
        'DEMO_ALLOWED_SEMANTIC_DATABASE',
        'DOCUMENT_STORAGE_PATH'
    )
    $previousEnvironment = @{}
    foreach ($name in $managedEnvironment) {
        $item = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        $previousEnvironment[$name] = @{
            Exists = ($null -ne $item)
            Value = if ($null -ne $item) { [string]$item.Value } else { $null }
        }
    }

    Set-Item -LiteralPath 'Env:DEMO_RESET_CONFIRMATION' -Value 'RESET-SP-901-DEMO'
    Set-Item -LiteralPath 'Env:DEMO_ALLOWED_DATABASE' -Value $Database
    Set-Item -LiteralPath 'Env:DEMO_ALLOWED_SEMANTIC_DATABASE' -Value $SemanticDatabase
    Set-Item -LiteralPath 'Env:DOCUMENT_STORAGE_PATH' -Value $StorageRoot

    $processExitCode = 1
    $locationPushed = $false
    try {
        Push-Location $BackendRoot
        $locationPushed = $true
        [int]$processExitCode = & $CommandRunner
    } finally {
        if ($locationPushed) {
            Pop-Location
        }
        foreach ($name in $managedEnvironment) {
            if ($previousEnvironment[$name].Exists) {
                Set-Item -LiteralPath "Env:$name" -Value $previousEnvironment[$name].Value
            } else {
                Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
            }
        }
    }
    return $processExitCode
}

if ($env:SPRING_PROFILES_ACTIVE -ne 'demo') {
    throw 'SPRING_PROFILES_ACTIVE must be exactly demo.'
}
if ([string]::IsNullOrWhiteSpace($env:DEMO_ACCOUNT_PASSWORD)) {
    throw 'DEMO_ACCOUNT_PASSWORD must be supplied securely.'
}
if (-not (Test-ExplicitDemoTargetName $AllowedDatabase)) {
    throw 'AllowedDatabase must be an explicitly isolated demo database name.'
}
if (-not (Test-ExplicitDemoTargetName $AllowedSemanticDatabase)) {
    throw 'AllowedSemanticDatabase must be an explicitly isolated demo database name.'
}
if ([string]::IsNullOrWhiteSpace($env:DB_URL) -or $env:DB_URL -notmatch "/$([regex]::Escape($AllowedDatabase))(?:\?|$)") {
    throw 'DB_URL does not target the explicitly allowed demo database.'
}
if ([string]::IsNullOrWhiteSpace($env:SEMANTIC_DB_URL) -or $env:SEMANTIC_DB_URL -notmatch "/$([regex]::Escape($AllowedSemanticDatabase))(?:\?|$)") {
    throw 'SEMANTIC_DB_URL does not target the explicitly allowed demo semantic database.'
}
if ([string]::IsNullOrWhiteSpace($env:DOCUMENT_STORAGE_PATH)) {
    throw 'DOCUMENT_STORAGE_PATH must identify a dedicated demo storage root.'
}
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\backend'))
$storageRoot = Resolve-DemoStorageRoot -ConfiguredPath $env:DOCUMENT_STORAGE_PATH -BackendRoot $backendRoot
Assert-DemoStorageRoot -StorageRoot $storageRoot

$processExitCode = Invoke-DemoResetCommand -BackendRoot $backendRoot -StorageRoot $storageRoot `
    -Database $AllowedDatabase -SemanticDatabase $AllowedSemanticDatabase -CommandRunner {
        & .\mvnw.cmd -B --no-transfer-progress -Pdemo-reset spring-boot:run | Out-Host
        return $LASTEXITCODE
    }

if ($processExitCode -ne 0) {
    [Console]::Error.WriteLine("Demo reset/bootstrap process exited with code $processExitCode.")
    exit $processExitCode
}

Write-Host 'Demo reset/bootstrap completed successfully.'
exit 0
