[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $repositoryRoot '.env'
$composeFile = Join-Path $repositoryRoot 'compose.release-candidate.yaml'

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'Repository root .env is missing.'
    }

    $values = @{}
    $duplicates = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(?:#|$)') { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=') { continue }

        $name = $Matches[1]
        if ($values.ContainsKey($name)) {
            if (-not $duplicates.Contains($name)) { $duplicates.Add($name) }
        }
        $value = $line.Substring($line.IndexOf('=') + 1).Trim()
        if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or
                ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }

    if ($duplicates.Count -gt 0) {
        throw ('Duplicate variable names in repository .env: ' + ($duplicates -join ', '))
    }
    return $values
}

function Assert-EnvValue {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Values,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if (-not $Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        throw "Required .env variable is missing or empty: $Name"
    }
}

$values = Read-EnvFile -Path $envFile
$requiredNames = @(
    'DB_USERNAME', 'DB_PASSWORD', 'MYSQL_ROOT_PASSWORD',
    'SEMANTIC_DB_USERNAME', 'SEMANTIC_DB_PASSWORD',
    'JWT_SECRET', 'DEMO_ACCOUNT_PASSWORD'
)
foreach ($name in $requiredNames) { Assert-EnvValue -Values $values -Name $name }

try {
    $jwtBytes = [Convert]::FromBase64String([string]$values.JWT_SECRET)
} catch {
    throw 'JWT_SECRET in repository .env must be Base64 encoded. Secret values were not displayed.'
}
if ($jwtBytes.Length -lt 32) {
    [Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
    throw 'JWT_SECRET in repository .env must decode to at least 32 bytes. Secret values were not displayed.'
}
[Array]::Clear($jwtBytes, 0, $jwtBytes.Length)

if ($values.DEMO_ACCOUNT_PASSWORD.Length -lt 6) {
    throw 'DEMO_ACCOUNT_PASSWORD in repository .env must contain at least 6 characters. Secret values were not displayed.'
}

foreach ($name in @('AI_CHAT_PROVIDER', 'AI_EMBEDDING_PROVIDER')) {
    if ($values.ContainsKey($name) -and $values[$name] -notin @('none', 'google-genai')) {
        throw "$name in repository .env must be none or google-genai. Secret values were not displayed."
    }
}
$hasGeminiKey = (-not [string]::IsNullOrWhiteSpace([string]$values['GEMINI_API_KEY'])) -or
    (-not [string]::IsNullOrWhiteSpace([string]$values['AI_API_KEY']))
if ((@($values['AI_CHAT_PROVIDER'], $values['AI_EMBEDDING_PROVIDER']) -contains 'google-genai') -and -not $hasGeminiKey) {
    throw 'A Google Gemini provider is enabled but both GEMINI_API_KEY and its supported AI_API_KEY alias are missing or empty.'
}

if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot '.gitignore') -Pattern '^\.env$' -Quiet)) {
    throw 'Repository .gitignore does not protect .env; refusing to start.'
}
if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot '.dockerignore') -Pattern '^\.env$' -Quiet)) {
    throw 'Repository .dockerignore does not exclude .env; refusing to build.'
}
$ignored = & git -C $repositoryRoot check-ignore --quiet -- .env
if ($LASTEXITCODE -ne 0) { throw 'Git does not currently ignore the repository .env; refusing to start.' }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is not available. Start Docker Desktop and verify docker is on PATH.'
}
& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable or access was denied; no SP-903 resources were changed.' }

# Existing SP-903 volumes may contain databases initialized with older credentials.
# Never replace or reset them implicitly; stop until credential consistency is confirmed.
$volumeList = & docker volume ls --format '{{.Name}}'
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Docker volumes; refusing to start without confirming SP-903 isolation.' }
$existingRcVolumes = @($volumeList | Where-Object { $_ -in @(
    'supplypilot-sp903-local-demo-mysql',
    'supplypilot-sp903-local-demo-pgvector',
    'supplypilot-sp903-local-demo-documents'
) })
if ($existingRcVolumes.Count -gt 0) {
    throw ('Existing SP-903 volume(s) require credential and ownership verification before startup: ' + ($existingRcVolumes -join ', ') + '. No volumes were changed.')
}

$composeArgs = @(
    'compose', '--project-name', 'supplypilot-sp903-local-demo',
    '--env-file', $envFile, '-f', $composeFile
)
$interpolationNames = @(
    'DB_USERNAME', 'DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'SEMANTIC_DB_USERNAME',
    'SEMANTIC_DB_PASSWORD', 'JWT_SECRET', 'DEMO_ACCOUNT_PASSWORD', 'GEMINI_API_KEY',
    'AI_API_KEY', 'AI_CHAT_PROVIDER', 'AI_EMBEDDING_PROVIDER', 'AI_MODEL',
    'GEMINI_MODEL', 'GEMINI_EMBEDDING_MODEL', 'SEMANTIC_DIMENSIONS',
    'SEMANTIC_GENERATION_KEY', 'SEMANTIC_READ_ENABLED', 'JWT_EXPIRATION_SECONDS',
    'AI_PROVIDER_TIMEOUT'
)
$originalEnvironment = @{}
foreach ($name in $interpolationNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Set-ProcessEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [switch]$Remove,
        [AllowEmptyString()][string]$Value
    )
    if ($Remove) {
        Remove-Item -LiteralPath ("Env:" + $Name) -ErrorAction SilentlyContinue
    } else {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}

Push-Location $repositoryRoot
try {
    # Compose gives the shell environment precedence over --env-file. Clear only
    # colliding names in this script process, then restore every original value.
    foreach ($name in $interpolationNames) {
        Set-ProcessEnvironmentValue -Name $name -Remove
    }

    & docker @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'SP-903 Compose configuration validation failed; no stack was started.' }

    & docker @composeArgs up --detach --build --wait --wait-timeout 300
    if ($LASTEXITCODE -ne 0) { throw 'SP-903 services did not all become healthy. No destructive cleanup was attempted.' }

    $serviceStatus = & docker @composeArgs ps --format json
    if ($LASTEXITCODE -ne 0) { throw 'Could not read SP-903 service health status.' }
    $statuses = @($serviceStatus | ForEach-Object { $_ | ConvertFrom-Json })
    $expectedServices = @('mysql', 'pgvector', 'backend', 'frontend')
    foreach ($service in $expectedServices) {
        $item = $statuses | Where-Object { $_.Service -eq $service } | Select-Object -First 1
        if (-not $item -or $item.State -ne 'running' -or ($item.Health -and $item.Health -ne 'healthy')) {
            throw "SP-903 service health verification failed for service: $service"
        }
    }
    Write-Output 'SP-903 Docker stack is running and all four services report healthy.'
    Write-Output 'Frontend: http://localhost:8081'
} finally {
    foreach ($name in $interpolationNames) {
        if ($null -eq $originalEnvironment[$name]) {
            Set-ProcessEnvironmentValue -Name $name -Remove
        } else {
            Set-ProcessEnvironmentValue -Name $name -Value $originalEnvironment[$name]
        }
    }
    Pop-Location
}
