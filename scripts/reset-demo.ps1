[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AllowedDatabase,
    [Parameter(Mandatory = $true)]
    [string]$AllowedSemanticDatabase
)

$ErrorActionPreference = 'Stop'

if ($env:SPRING_PROFILES_ACTIVE -ne 'demo') {
    throw 'SPRING_PROFILES_ACTIVE must be exactly demo.'
}
if ([string]::IsNullOrWhiteSpace($env:DEMO_ACCOUNT_PASSWORD)) {
    throw 'DEMO_ACCOUNT_PASSWORD must be supplied securely.'
}
if ([string]::IsNullOrWhiteSpace($env:DB_URL) -or $env:DB_URL -notmatch "/$([regex]::Escape($AllowedDatabase))(?:\?|$)") {
    throw 'DB_URL does not target the explicitly allowed demo database.'
}
if ([string]::IsNullOrWhiteSpace($env:SEMANTIC_DB_URL) -or $env:SEMANTIC_DB_URL -notmatch "/$([regex]::Escape($AllowedSemanticDatabase))(?:\?|$)") {
    throw 'SEMANTIC_DB_URL does not target the explicitly allowed demo semantic database.'
}
if ([string]::IsNullOrWhiteSpace($env:DOCUMENT_STORAGE_PATH) -or
    $env:DOCUMENT_STORAGE_PATH -notmatch '(?i)(^|[\\/_.-])demo($|[\\/_.-])') {
    throw 'DOCUMENT_STORAGE_PATH must explicitly identify a demo path.'
}

$managedEnvironment = @(
    'DEMO_RESET_CONFIRMATION',
    'DEMO_ALLOWED_DATABASE',
    'DEMO_ALLOWED_SEMANTIC_DATABASE'
)
$previousEnvironment = @{}
foreach ($name in $managedEnvironment) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable(
        $name,
        [EnvironmentVariableTarget]::Process
    )
}

[Environment]::SetEnvironmentVariable(
    'DEMO_RESET_CONFIRMATION',
    'RESET-SP-901-DEMO',
    [EnvironmentVariableTarget]::Process
)
[Environment]::SetEnvironmentVariable(
    'DEMO_ALLOWED_DATABASE',
    $AllowedDatabase,
    [EnvironmentVariableTarget]::Process
)
[Environment]::SetEnvironmentVariable(
    'DEMO_ALLOWED_SEMANTIC_DATABASE',
    $AllowedSemanticDatabase,
    [EnvironmentVariableTarget]::Process
)

$processExitCode = 1
$locationPushed = $false
try {
    Push-Location (Join-Path $PSScriptRoot '..\backend')
    $locationPushed = $true
    & .\mvnw.cmd -B --no-transfer-progress spring-boot:run `
        '-Dspring-boot.run.main-class=com.supplypilot.demo.DemoResetApplication'
    $processExitCode = $LASTEXITCODE
} finally {
    if ($locationPushed) {
        Pop-Location
    }
    foreach ($name in $managedEnvironment) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $previousEnvironment[$name],
            [EnvironmentVariableTarget]::Process
        )
    }
}

if ($processExitCode -ne 0) {
    [Console]::Error.WriteLine("Demo reset/bootstrap process exited with code $processExitCode.")
    exit $processExitCode
}

Write-Host 'Demo reset/bootstrap completed successfully.'
exit 0
