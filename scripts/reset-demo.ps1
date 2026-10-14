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
if ([string]::IsNullOrWhiteSpace($env:DOCUMENT_STORAGE_PATH) -or $env:DOCUMENT_STORAGE_PATH -notmatch '(?i)demo') {
    throw 'DOCUMENT_STORAGE_PATH must explicitly identify a demo path.'
}

$env:DEMO_RESET_ENABLED = 'true'
$env:DEMO_RESET_CONFIRMATION = 'RESET-SP-901-DEMO'
$env:DEMO_ALLOWED_DATABASE = $AllowedDatabase
$env:DEMO_ALLOWED_SEMANTIC_DATABASE = $AllowedSemanticDatabase

Push-Location (Join-Path $PSScriptRoot '..\backend')
try {
    & .\mvnw.cmd -B --no-transfer-progress spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Demo reset/bootstrap process exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
